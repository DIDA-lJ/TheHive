package org.thp.thehive.controllers.v1

import scala.util.{Success, Try}

import play.api.libs.json.Json
import play.api.test.{FakeRequest, NoMaterializer, PlaySpecification}

import akka.stream.Materializer
import org.specs2.mock.Mockito
import org.specs2.specification.core.{Fragment, Fragments}
import org.thp.scalligraph.AppBuilder
import org.thp.scalligraph.auth._
import org.thp.scalligraph.models.{Database, DatabaseProviders}
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.dto.v1.{InputUser, OutputUser}
import org.thp.thehive.models._

case class TestUser(login: String, name: String, profile: String, permissions: Set[String], organisation: String)

object TestUser {

  def apply(user: OutputUser): TestUser =
    TestUser(user.login, user.name, user.profile, user.permissions, user.organisation)
}

class DummyAuthSrv extends AuthSrv {
  val name: String                                                                                              = "dummy"
  override def setPassword(username: String, newPassword: String)(implicit authContext: AuthContext): Try[Unit] = Success(())
}

class UserCtrlTest extends PlaySpecification with Mockito {
  implicit val mat: Materializer = NoMaterializer

  Fragments.foreach(new DatabaseProviders().list) { dbProvider =>
    val app: AppBuilder = TestAppBuilder(dbProvider)
      .addConfiguration("auth.providers = [{name:local},{name:key},{name:header, userHeader:user}]")
    step(setupDatabase(app)) ^ specs(dbProvider.name, app) ^ step(teardownDatabase(app))
  }

  def setupDatabase(app: AppBuilder): Try[Unit] =
    app.instanceOf[DatabaseBuilder].build()(app.instanceOf[Database], app.instanceOf[UserSrv].getSystemAuthContext)

  def teardownDatabase(app: AppBuilder): Unit = app.instanceOf[Database].drop()

  def specs(name: String, app: AppBuilder): Fragment = {
    val userCtrl: UserCtrl = app.instanceOf[UserCtrl]

    s"[$name] user controller" should {

      "return current user information" in {
        val request = FakeRequest("GET", "/api/v1/user/current")
          .withHeaders("user" -> "admin@thehive.local")
        val result = userCtrl.current(request)
        status(result) must_=== 200
        val resultCase = contentAsJson(result).as[OutputUser]
        val expected = TestUser(
          login = "admin@thehive.local",
          name = "Default admin user",
          profile = "admin",
          permissions = Permissions.all.map(_.toString),
          organisation = "default"
        )

        TestUser(resultCase) must_=== expected
      }

      "create a new user" in {
        val request = FakeRequest("POST", "/api/v1/user")
          .withJsonBody(
            Json.toJson(
              InputUser(
                login = "test_user_1@thehive.local",
                name = "create user test",
                password = Some("azerty"),
                profile = "read-only",
                organisation = Some("default")
              )
            )
          )
          .withHeaders("user" -> "admin@thehive.local")
        val result = userCtrl.create(request)
        status(result) must_=== 201
        val resultCase = contentAsJson(result).as[OutputUser]
        val expected = TestUser(
          login = "test_user_1@thehive.local",
          name = "create user test",
          profile = "read-only",
          permissions = Set.empty,
          organisation = "default"
        )

        TestUser(resultCase) must_=== expected
      }

      "refuse to create an user if the permission doesn't contain ManageUser right" in {
        val request = FakeRequest("POST", "/api/v1/user")
          .withJsonBody(
            Json.toJson(
              InputUser(
                login = "test_user_3",
                name = "create user test",
                password = Some("azerty"),
                profile = "analyst",
                organisation = Some("cert")
              )
            )
          )
          .withHeaders("user" -> "user2@thehive.local")
        val result = userCtrl.create(request)
        status(result) must_=== 403
      }

      "get a user in the same organisation" in {
        val request = FakeRequest("GET", s"/api/v1/user/user2@thehive.local").withHeaders("user" -> "user1@thehive.local")
        val result  = userCtrl.get("user2@thehive.local")(request)
        status(result) must_=== 200
        val resultCase = contentAsJson(result).as[OutputUser]
        val expected = TestUser(
          login = "user2@thehive.local",
          name = "U",
          profile = "read-only",
          permissions = Set.empty,
          organisation = "cert"
        )

        TestUser(resultCase) must_=== expected
      }

      "get a user of a visible organisation" in {
        val request = FakeRequest("GET", s"/api/v1/user/user1@thehive.local").withHeaders("user" -> "user2@thehive.local")
        val result  = userCtrl.get("user1@thehive.local")(request)
        status(result) must_=== 200
        val resultCase = contentAsJson(result).as[OutputUser]
        val expected = TestUser(
          login = "user1@thehive.local",
          name = "Thomas",
          profile = "analyst",
          permissions = Set(Permissions.manageAlert, Permissions.manageCase),
          organisation = "cert"
        )

        TestUser(resultCase) must_=== expected
      }.pendingUntilFixed("Organisation visibility needs to be fixed")

      "refuse to get a user of an invisible organisation" in {
        val request = FakeRequest("GET", s"/api/v1/user/admin@thehive.local").withHeaders("user" -> "user1@thehive.local")
        val result  = userCtrl.get("admin@thehive.local")(request)
        status(result) must_=== 404
      }

      "update an user" in pending
      "set password" in pending
      "change password" in pending
      "get key" in pending
      "remove key" in pending
      "renew key" in pending
    }
  }
}
