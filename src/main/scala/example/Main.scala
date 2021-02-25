package example

import caliban.GraphQL._
import caliban.schema.GenericSchema
import caliban.{CalibanError, GraphQLInterpreter, Http4sAdapter, RootResolver}
import example.Main.{AuthTask, Authorization}
import org.http4s.{HttpRoutes, headers}
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.{Router, ServiceErrorHandler}
import org.http4s.util.CaseInsensitiveString
import zio._
import zio.interop.catz._
import zio.interop.catz.implicits._

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext


case class User(id: UUID, name: String)
case class ClientSession(id: UUID, ipAddress: String)

class AuthService {

  def findUserAndClientSessionByToken(token: String): Task[(User,ClientSession)] = for {
    user <- Task.effect(User(id = UUID.randomUUID(), name = "Jared Dobson")) //simulated db call
    clientSession <- Task.effect(ClientSession(id = UUID.randomUUID(), ipAddress = "test"))
  } yield (user, clientSession)
}

object AuthMiddleware {

  def apply(route: HttpRoutes[AuthTask], authService: AuthService): HttpRoutes[Task] =
    Http4sAdapter.provideLayerFromRequest(
      route,
      request =>
        request.headers.get(headers.Authorization) match {
          case Some(foundToken) => Runtime.global.unsafeRun(headerToZLayer(foundToken.value, authService))
          case None =>
            ZLayer.fail(
              new Throwable(s"Missing authorization header for ${request.uri} - ${request.params}")
            )
        }
    )

  def headerToZLayer(
                      authToken: String,
                      authService: AuthService
                    ): ZIO[Any, Throwable, Layer[Nothing, Authorization]] =
    for {
      userTuple <- authService.findUserAndClientSessionByToken(
        authToken.replace("Bearer ", "")
      )
      secureLayer = provideSecureEnv(userTuple)
    } yield secureLayer

  private def provideSecureEnv(userTuple: (User, ClientSession)): Layer[Nothing, Authorization] =
    ZLayer.succeed(new Authorization.Service {
      override val currentUser: User = userTuple._1
      override val clientSession: ClientSession = userTuple._2
    })
}


object Main extends CatsApp {


  type AuthTask[A] = RIO[Authorization, A]

  type SecureAPIEnv = ZEnv with Authorization

  type Authorization = Has[Authorization.Service]

  object Authorization extends Serializable {

    trait Service extends Serializable {
      val currentUser: User
      val clientSession: ClientSession
    }
  }
  object secureApiSchema extends GenericSchema[Authorization]
 case class MissingToken() extends Throwable
  // http4s error handler to customize the response for our throwable
  object dsl extends Http4sDsl[Task]
  import dsl._
  import secureApiSchema._
  case class Query(getUser: RIO[Authorization, User])
  private val resolver = RootResolver(Query(ZIO.access[Authorization](_.get[Authorization.Service].currentUser)))
  private val api      = graphQL(resolver)
  val errorHandler: ServiceErrorHandler[Task] = _ => { case MissingToken() => Forbidden() }

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (for {
      interpreter: GraphQLInterpreter[Authorization, CalibanError] <- api.interpreter
      authService = new AuthService()
      route       = AuthMiddleware(Http4sAdapter.makeHttpService(interpreter), authService)
      _ <- BlazeServerBuilder[Task](ExecutionContext.global)
        .bindHttp(8088, "localhost")
        .withHttpApp(Router[Task]("/api/graphql" -> route).orNotFound)
        .resource
        .toManaged
        .useForever
    } yield ()).exitCode
}
