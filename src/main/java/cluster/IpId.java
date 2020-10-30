package cluster;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import akka.actor.Address;
import akka.actor.typed.ActorSystem;

public abstract class IpId {
  public final String ip;
  public final String id;

  @JsonCreator
  public IpId(
    @JsonProperty("ip") String ip,
    @JsonProperty("id") String id) {
    this.ip = ip;
    this.id = id;
  }

  private static String ipOf(ActorSystem<?> actorSystem) {
    return actorSystem.address().getHost().orElse("err");
  }

  private static String idOf(ActorSystem<?> actorSystem) {
    return idOf(actorSystem.address());
  }

  private static String idOf(Address address) {
    return idOf(address.getHost().orElse("err"));
  }

  private static String idOf(String ip) {
    final String[] ips = ip.split("\\.");

    if (ips.length < 1) {
      throw new RuntimeException(String.format("Akka host (%s) must be a valid IPv4 address.", ip));
    }
    return ips[ips.length - 1];
  }

  @Override
  public String toString() {
    return String.format("%s[%s, %s]", getClass().getSimpleName(), ip, id);
  }

  public static class Client extends IpId {
    @JsonCreator
    public Client(
      @JsonProperty("ip") String ip,
      @JsonProperty("id") String id) {
      super(ip, id);
    }

    static Client of(ActorSystem<?> actorSystem) {
      return new Client(ipOf(actorSystem), idOf(actorSystem));
    }
  };

  public static class Server extends IpId {
    @JsonCreator
    public Server(
      @JsonProperty("ip") String ip,
      @JsonProperty("id") String id) {
      super(ip, id);
    }

    static Server of(ActorSystem<?> actorSystem) {
      return new Server(ipOf(actorSystem), idOf(actorSystem));
    }
  }
}
