package org.thp.scalligraph.services.auth

import java.net.ConnectException
import java.util

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

import play.api.mvc.RequestHeader
import play.api.{Configuration, Logger}

import javax.inject.{Inject, Singleton}
import javax.naming.Context
import javax.naming.directory._
import org.thp.scalligraph.auth.{AuthCapability, AuthContext, AuthSrv, UserSrv}
import org.thp.scalligraph.{AuthenticationError, AuthorizationError}

case class LdapConnection(serverNames: Seq[String], useSSL: Boolean, bindDN: String, bindPW: String, baseDN: String, filter: String) {

  private[LdapConnection] lazy val logger = Logger(classOf[LdapAuthSrv])

  private val noLdapServerAvailableException = AuthenticationError("No LDAP server found")

  private def isFatal(t: Throwable): Boolean = t match {
    case null                             ⇒ true
    case `noLdapServerAvailableException` ⇒ false
    case _: ConnectException              ⇒ false
    case _                                ⇒ isFatal(t.getCause)
  }

  private def connect[A](username: String, password: String)(f: InitialDirContext ⇒ Try[A]): Try[A] =
    serverNames.foldLeft[Try[A]](Failure(noLdapServerAvailableException)) {
      case (Failure(e), serverName) if !isFatal(e) ⇒
        val protocol = if (useSSL) "ldaps://" else "ldap://"
        val env      = new util.Hashtable[Any, Any]
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
        env.put(Context.PROVIDER_URL, protocol + serverName)
        env.put(Context.SECURITY_AUTHENTICATION, "simple")
        env.put(Context.SECURITY_PRINCIPAL, username)
        env.put(Context.SECURITY_CREDENTIALS, password)
        Try {
          val ctx = new InitialDirContext(env)
          try f(ctx)
          finally ctx.close()
        }.flatten
      case (failure @ Failure(e), _) ⇒
        logger.debug("LDAP connect error", e)
        failure
      case (r, _) ⇒ r
    }

  private def getUserDN(ctx: InitialDirContext, username: String): Try[String] =
    Try {
      val controls = new SearchControls()
      controls.setSearchScope(SearchControls.SUBTREE_SCOPE)
      controls.setCountLimit(1)
      val searchResult = ctx.search(baseDN, filter, Array[Object](username), controls)
      if (searchResult.hasMore) searchResult.next().getNameInNamespace
      else throw AuthenticationError("User not found in LDAP server")
    }

  def authenticate(username: String, password: String): Try[Unit] =
    connect(bindDN, bindPW) { ctx ⇒
      getUserDN(ctx, username)
    }.flatMap { userDN ⇒
      connect(userDN, password)(_ ⇒ Success(()))
    }

  def changePassword(username: String, oldPassword: String, newPassword: String): Try[Unit] =
    connect(bindDN, bindPW) { ctx ⇒
      getUserDN(ctx, username)
    }.flatMap { userDN ⇒
      connect(userDN, oldPassword) { ctx ⇒
        val mods = Array(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userPassword", newPassword)))
        Try(ctx.modifyAttributes(userDN, mods))
      }
    }
}

object LdapConnection {

  def apply(configuration: Configuration): LdapConnection =
    (for {
      bindDN ← configuration.getOptional[String]("auth.ldap.bindDN")
      bindPW ← configuration.getOptional[String]("auth.ldap.bindPW")
      baseDN ← configuration.getOptional[String]("auth.ldap.baseDN")
      filter ← configuration.getOptional[String]("auth.ldap.filter")
      serverNames = configuration.getOptional[String]("auth.ldap.serverName").fold[Seq[String]](Nil)(s ⇒ Seq(s)) ++
        configuration.getOptional[Seq[String]]("auth.ldap.serverNames").getOrElse(Nil)
      useSSL = configuration.get[Boolean]("auth.ldap.useSSL")

    } yield LdapConnection(serverNames, useSSL, bindDN, bindPW, baseDN, filter))
      .getOrElse(LdapConnection(Nil, useSSL = false, "", "", "", ""))
}

@Singleton
class LdapAuthSrv(ldapConnection: LdapConnection, userSrv: UserSrv, implicit val ec: ExecutionContext) extends AuthSrv {

  @Inject() def this(configuration: Configuration, userSrv: UserSrv, ec: ExecutionContext) = this(LdapConnection(configuration), userSrv, ec)

  private[LdapAuthSrv] lazy val logger = Logger(getClass)

  val name                                             = "ldap"
  override val capabilities: Set[AuthCapability.Value] = Set(AuthCapability.changePassword)

  override def authenticate(username: String, password: String, organisation: Option[String])(implicit request: RequestHeader): Try[AuthContext] =
    ldapConnection
      .authenticate(username, password)
      .flatMap(_ ⇒ userSrv.getFromId(request, username, organisation))
      .recoverWith {
        case t ⇒
          logger.error("LDAP authentication failure", t)
          Failure(AuthenticationError("Authentication failure"))
      }

  override def changePassword(username: String, oldPassword: String, newPassword: String)(implicit authContext: AuthContext): Try[Unit] =
    ldapConnection
      .changePassword(username, oldPassword, newPassword)
      .recoverWith {
        case t ⇒
          logger.error("LDAP change password failure", t)
          Failure(AuthorizationError("Change password failure"))
      }
}
