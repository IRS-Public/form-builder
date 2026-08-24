package gov.irs.formbuilder.exceptions

/** Thrown by the flow parser when the flow XML is structurally wrong. */
case class InvalidFormConfig(message: String) extends Exception(message)
