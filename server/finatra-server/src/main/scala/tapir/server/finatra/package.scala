package tapir.server
import java.nio.charset.Charset

import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.inject.Logging
import com.twitter.util.Future
import tapir.EndpointInput.PathCapture
import tapir.internal.server.{DecodeInputs, DecodeInputsResult, InputValues}
import tapir.internal.{SeqToParams, _}
import tapir.{DecodeFailure, DecodeResult, Endpoint, EndpointIO, EndpointInput}

import scala.reflect.ClassTag
import scala.util.control.NonFatal

package object finatra {
  implicit class RichFinatraEndpoint[I, E, O](e: Endpoint[I, E, O, Nothing]) extends Logging {
    def toRoute(logic: I => Future[Either[E, O]])(implicit serverOptions: FinatraServerOptions): FinatraRoute = {

      val handler = { request: Request =>
        def decodeBody(result: DecodeInputsResult): Future[DecodeInputsResult] = {
          result match {
            case values: DecodeInputsResult.Values =>
              values.bodyInput match {
                case Some(bodyInput @ EndpointIO.Body(codec, _)) =>
                  new FinatraRequestToRawBody(serverOptions)
                    .apply(codec.meta.rawValueType, request.content, request.charset.map(Charset.forName), request)
                    .map { rawBody =>
                      codec.safeDecode(Some(rawBody)) match {
                        case DecodeResult.Value(bodyV) => values.value(bodyInput, bodyV)
                        case failure: DecodeFailure    => DecodeInputsResult.Failure(bodyInput, failure): DecodeInputsResult
                      }
                    }
                case None => Future.value(values)
              }
            case failure: DecodeInputsResult.Failure => Future.value(failure)
          }
        }

        def valuesToResponse(values: DecodeInputsResult.Values): Future[Response] = {
          val i = SeqToParams(InputValues(e.input, values.values)).asInstanceOf[I]
          logic(i)
            .map {
              case Right(result) => OutputToFinatraResponse(e.output, result).toResponse
              case Left(err)     => OutputToFinatraResponse(e.errorOutput, err, None, Status(ServerDefaults.errorStatusCode)).toResponse
            }
            .onFailure {
              case NonFatal(ex) =>
                error(ex)
            }
        }

        def handleDecodeFailure(
            e: Endpoint[_, _, _, _],
            req: Request,
            input: EndpointInput.Single[_],
            failure: DecodeFailure
        ): Response = {
          val handling = serverOptions.decodeFailureHandler(req, input, failure)

          handling match {
            case DecodeFailureHandling.NoMatch =>
              serverOptions.loggingOptions.decodeFailureNotHandledMsg(e, failure, input).foreach(debug(_))
              Response(Status.BadRequest)
            case DecodeFailureHandling.RespondWithResponse(output, value) =>
              serverOptions.loggingOptions.decodeFailureHandledMsg(e, failure, input, value).foreach {
                case (msg, Some(t)) => debug(msg, t)
                case (msg, None)    => debug(msg)
              }

              OutputToFinatraResponse(output, value, None, Status(ServerDefaults.errorStatusCode)).toResponse
          }
        }

        decodeBody(DecodeInputs(e.input, new FinatraDecodeInputsContext(request))).flatMap {
          case values: DecodeInputsResult.Values          => valuesToResponse(values)
          case DecodeInputsResult.Failure(input, failure) => Future.value(handleDecodeFailure(e, request, input, failure))
        }
      }

      FinatraRoute(handler, e.input.path)
    }

    def toRouteRecoverErrors(logic: I => Future[O])(
        implicit eIsThrowable: E <:< Throwable,
        eClassTag: ClassTag[E]
    ): FinatraRoute = {
      e.toRoute { i: I =>
        logic(i).map(Right(_)).handle {
          case ex if eClassTag.runtimeClass.isInstance(ex) => Left(ex.asInstanceOf[E])
        }
      }
    }
  }

  implicit class SuperRichEndpointInput[I](input: EndpointInput[I]) {
    def path: String = {
      val p = input
        .asVectorOfBasicInputs()
        .collect {
          case segment: EndpointInput.PathSegment => segment.show
          case PathCapture(_, Some(name), _)      => s"/:$name"
          case PathCapture(_, _, _)               => "/:param"
          case EndpointInput.PathsCapture(_)      => "/:*"
        }
        .mkString
      if (p.isEmpty) "/:*" else p
    }
  }
}
