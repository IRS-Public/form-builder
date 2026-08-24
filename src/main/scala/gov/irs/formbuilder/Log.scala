package gov.irs.formbuilder

/** Build-time console logging. Everything goes to stderr, so it stays out of a redirected stdout. */
object Log {
  def info(message: String): Unit =
    System.err.println(s"[INFO] $message")

  def warn(message: String): Unit =
    System.err.println(s"[WARN] $message")
}
