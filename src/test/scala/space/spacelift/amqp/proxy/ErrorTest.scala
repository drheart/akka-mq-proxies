package space.spacelift.amqp.proxy

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{Actor, Props, ActorSystem}
import akka.pattern.{AskTimeoutException, ask}
import concurrent.{Await, ExecutionContext}
import concurrent.duration._
import com.rabbitmq.client.ConnectionFactory
import space.spacelift.amqp.{Amqp, RpcClient, RpcServer, ConnectionOwner}
import space.spacelift.amqp.Amqp._
import serializers.JsonSerializer
import space.spacelift.amqp.Amqp.ChannelParameters
import scala.Some
import space.spacelift.amqp.Amqp.ExchangeParameters
import space.spacelift.amqp.Amqp.AddBinding
import space.spacelift.amqp.Amqp.QueueParameters
import java.util.concurrent.TimeUnit


object ErrorTest {
  case class ErrorRequest(foo: String)
}

@RunWith(classOf[JUnitRunner])
class ErrorTest extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers {
  import ErrorTest.ErrorRequest
  implicit val timeout: akka.util.Timeout = 5 seconds

  "AMQP Proxy" should {

    "handle server errors in" in {
      import ExecutionContext.Implicits.global

      pending
      val connFactory = new ConnectionFactory()
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "error", passive = false, autodelete = true)
      val channelParams = ChannelParameters(qos = 1)


      val nogood = system.actorOf(Props(new Actor() {
        def receive = {
          case ErrorRequest(foo) => sender ! akka.actor.Status.Failure(new RuntimeException("crash"))
        }
      }))
      // create an AMQP proxy server which consumes messages from the "error" queue and passes
      // them to our nogood actor
      val server = ConnectionOwner.createChildActor(
        conn,
        RpcServer.props(queue, exchange, "error", new AmqpProxy.ProxyServer(nogood), channelParams))

      // create an AMQP proxy client in front of the "error queue"
      val client = ConnectionOwner.createChildActor(conn, RpcClient.props())
      val proxy = system.actorOf(
        AmqpProxy.ProxyClient.props(client, "amq.direct", "error", JsonSerializer),
        name = "proxy")

      Amqp.waitForConnection(system, server).await()

      val thrown = the [AmqpProxyException] thrownBy (Await.result(proxy ? ErrorRequest("test"), 5 seconds))
      thrown.getMessage should be("crash")
    }

    "handle client-side serialization errors" in {
      pending
      val connFactory = new ConnectionFactory()
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val client = ConnectionOwner.createChildActor(conn, RpcClient.props())
      val proxy = system.actorOf(AmqpProxy.ProxyClient.props(client, "amq.direct", "client_side_error", JsonSerializer))

      val badrequest = Map(1 -> 1) // lift-json will not serialize this, Map keys must be Strings
      val thrown = the [AmqpProxyException] thrownBy (Await.result(proxy ? badrequest, 5 seconds))
      thrown.getMessage should include("Serialization")
    }

    "handle server-side timeouts" in {
      import ExecutionContext.Implicits.global
      
      val connFactory = new ConnectionFactory()
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))

      // create an actor that does nothing
      val donothing = system.actorOf(Props(new Actor() {
        def receive = {
          case msg => {}
        }
      }))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "donothing", passive = false, autodelete = true)
//      val server = ConnectionOwner.createChildActor(
//        conn,
//        RpcServer.props(queue, exchange, "donothing", new AmqpProxy.ProxyServer(donothing, timeout = 1 second), channelParams))
      val server = ConnectionOwner.createChildActor(conn, RpcServer.props(new AmqpProxy.ProxyServer(donothing, timeout = 1 second), channelParams = Some(ChannelParameters(qos = 1))))
      Amqp.waitForConnection(system, server).await(5, TimeUnit.SECONDS)
      server ! AddBinding(Binding(exchange, queue, routingKey = "donothing"))
      val Amqp.Ok(AddBinding(_), _) = receiveOne(1 second)
      val client = ConnectionOwner.createChildActor(conn, RpcClient.props())
      val proxy = system.actorOf(AmqpProxy.ProxyClient.props(client, "amq.direct", "donothing", JsonSerializer, timeout = 2 seconds))

      Amqp.waitForConnection(system, server, client).await()
      the [AskTimeoutException] thrownBy Await.result(proxy ? "test", 5 seconds)
    }
  }
}
