package cn.xuyinyin.magic.workflow.nodes

import spray.json.{JsString, JsValue}

object JdbcPasswordResolver {
  def resolve(
    fields: Map[String, JsValue],
    getenv: String => Option[String] = sys.env.get
  ): String = {
    val inlinePassword = nonEmptyString(fields, "password")
    val passwordEnv = nonEmptyString(fields, "passwordEnv")

    (inlinePassword, passwordEnv) match {
      case (Some(password), None) => password
      case (None, Some(environmentName)) =>
        getenv(environmentName).filter(_.nonEmpty)
          .getOrElse(throw new IllegalArgumentException(s"passwordEnv $environmentName is not set or is empty"))
      case (None, None) =>
        throw new IllegalArgumentException("exactly one non-empty password or passwordEnv configuration is required")
      case (Some(_), Some(_)) =>
        throw new IllegalArgumentException("exactly one non-empty password or passwordEnv configuration is required")
    }
  }

  private def nonEmptyString(fields: Map[String, JsValue], key: String): Option[String] =
    fields.get(key) match {
      case Some(JsString(value)) if value.nonEmpty => Some(value)
      case Some(JsString(_)) => throw new IllegalArgumentException(s"$key must be a non-empty string")
      case Some(_) => throw new IllegalArgumentException(s"$key must be a string")
      case None => None
    }
}
