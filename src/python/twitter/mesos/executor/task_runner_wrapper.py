import os
from twitter.common import app
from twitter.common.http.mirror_file import MirrorFile
from twitter.thermos.config.schema.loader import ThermosTaskWrapper

app.add_option("--checkpoint_root", dest="checkpoint_root", metavar="PATH",
               default="/var/run/thermos",
               help="the path where we will store workflow logs and checkpoints")

class LocalFile(object):
  """
    Local analogue of MirrorFile.
  """
  def __init__(self, filename):
    self._name = filename

  def refresh(self):
    return os.path.exists(self._name)

  def filename(self):
    return self._name

class TaskRunnerWrapper(object):
  TEMPDIR = None
  PEX_NAME = 'thermos_run.pex'

  class TaskError(Exception):
    pass

  def __init__(self, task_id, thermos_task, mesos_ports, checkpoint_root=None):
    """
      :task_id       => task_id assigned by scheduler
      :thermos_task  => twitter.thermos.config.schema.Task object
      :mesos_ports   => { name => port } dictionary
    """
    self._popen = None
    self._task_id = task_id
    self._task = thermos_task
    self._task_filename = TaskRunnerWrapper.dump_task(self._task)
    self._ports = mesos_ports
    self._checkpoint_root = checkpoint_root or app.get_options().checkpoint_root
    self._enable_chroot = False
    if TaskRunnerWrapper.TEMPDIR is None:
      TaskRunnerWrapper.TEMPDIR = tempfile.mkdtemp()

  @staticmethod
  def dump_task(task):
    with temporary_file(cleanup=False) as fp:
      filename = fp.name
      ThermosTaskWrapper(task).to_file(filename)
    return filename

  def start(self):
    """
      Fork the task runner.

      REQUIRES SUBCLASSES TO DEFINE:
        self._sandbox (SandboxManager)
        self._runner_pex (MirrorFile)
    """
    assert hasattr(self, '_sandbox')
    assert hasattr(self, '_runner_pex')

    log.info('Acquiring runner pex: %s' % (
      'Success' if self._runner_pex.refresh() else 'Already up to date'))
    chmod_plus_x(self._runner_pex.filename())

    self._monitor = TaskMonitor(TaskPath(root=self._checkpoint_root), self._task_id)

    try:
      self._sandbox.create(thermos_task)
    except Exception as e:
      log.fatal('Could not construct sandbox: %s' % e)
      raise TaskRunnerWrapper.TaskError('Could not construct sandbox: %s' % e)

    options = app.get_options()
    params = dict(log_dir=LogOptions.log_dir(),
                  checkpoint_root=self._checkpoint_root,
                  sandbox=self._sandbox.root(),
                  task_id=self._task_id,
                  thermos_json=self._task_filename)

    if getpass.getuser() == 'root':
      params.update(setuid=self._role)

    cmdline_args = [self._runner_pex.filename()]
    cmdline_args.extend('--%s=%s' % (flag, value) for flag, value in params.items())
    cmdline_args.extend([
      '--enable_scribe_exception_hook',
      '--scribe_exception_category=thermos_runner_exceptions'])
    if self._enable_chroot:
      cmdline_args.extend(['--enable_chroot'])
    log.info('Forking off runner with cmdline: %s' % ' '.join(cmdline_args))
    self._popen = subprocess.Popen(cmdline_args)

  def state(self):
    return self._monitor.get_state()

  def is_alive(self):
    """
      Is the process underlying the Thermos task runner alive?
    """
    return self._popen is not None and self._popen.poll() is None

  def kill(self):
    """
      Kill the underlying runner process.  Returns True if killed, False if
      it exited on its own.
    """
    assert self._popen is not None
    if self._popen.poll() is None:
      self._popen.send_signal(signal.SIGINT)
      self._popen.wait()
    return self._popen.poll() == -signal.SIGINT

  def quitquitquit(self):
    """Bind to the process tree of a Thermos task and kill it with impunity."""
    runner = TaskRunner(self._task, self._sandbox.root(), self._checkpoint_root, self._task_id)
    runner.kill()


class ProductionTaskRunner(TaskRunnerWrapper):
  SVN_REPO = 'svn.twitter.biz'
  SVN_PATH = '/science-binaries/home/thermos'

  def __init__(self, *args, **kwargs):
    TaskRunnerWrapper.__init__(self, *args, **kwargs)
    self._sandbox = SandboxManager.get(task_id)
    self._runner_pex = MirrorFile(
      ProductionTaskRunner.SVN_REPO,
      os.path.join(ProductionTaskRunner.SVN_PATH, TaskRunnerWrapper.PEX_NAME),
      os.path.join(ProductionTaskRunner.TEMPDIR, TaskRunnerWrapper.PEX_NAME),
      https=True)
    self._enable_chroot = True


class AngrybirdTaskRunner(object):
  def __init__(self, *args, **kwargs):
    TaskRunnerWrapper.__init__(self, *args, **kwargs)
    self._angrybird_home = os.environ['ANGRYBIRD_HOME']
    self._sandbox_root = os.path.join(self._angrybird_home, 'logs/thermos/lib')
    self._checkpoint_root = os.path.join(self._angrybird_home, 'logs/thermos/run')
    self._runner_pex = LocalFile(os.path.join(self._angrybird_home,
                                              'science/dist',
                                              TaskRunnerWrapper.PEX_NAME))
