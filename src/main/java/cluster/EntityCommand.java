package cluster;

import com.fasterxml.jackson.annotation.JsonCreator;
import akka.actor.typed.ActorSystem;

public interface EntityCommand extends CborSerializable {

  public static class ChangeValue implements EntityCommand {
    public final String id;
    public final Object value;
    public final long nsStart;

    @JsonCreator
    public ChangeValue(String id, Object value, long nsStart) {
      this.id = id;
      this.value = value;
      this.nsStart = nsStart;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s]", getClass().getSimpleName(), id, value);
    }
  }

  public static class ChangeValueAck implements EntityCommand {
    public final String id;
    public final Object value;
    public final long nsStart;
    public final String message;
    public final int httpStatusCode;

    @JsonCreator
    public ChangeValueAck(String id, Object value, long nsStart, String message, int httpStatusCode) {
      this.id = id;
      this.value = value;
      this.nsStart = nsStart;
      this.message = message;
      this.httpStatusCode = httpStatusCode;
    }

    @Override
    public String toString() {
      return String.format("%s[%,d, %s, %s, %s, %d]", getClass().getSimpleName(), nsStart, id, value, message, httpStatusCode);
    }
  }

  public static class GetValue implements EntityCommand {
    public final String id;
    public final long nsStart;

    @JsonCreator
    public GetValue(String id, long nsStart) {
      this.id = id;
      this.nsStart = nsStart;
    }

    @Override
    public String toString() {
      return String.format("%s[%,d, %s]", getClass().getSimpleName(), nsStart, id);
    }
  }

  public static class GetValueAck implements EntityCommand {
    public final String id;
    public final Object value;
    public final long nsStart;
    public final String message;
    public final int httpStatusCode;

    @JsonCreator
    public GetValueAck(String id, Object value, long nsStart, String message, int httpStatusCode) {
      this.id = id;
      this.value = value;
      this.nsStart = nsStart;
      this.message = message;
      this.httpStatusCode = httpStatusCode;
    }

    @Override
    public String toString() {
      return String.format("%s[%,d, %s, %s, %s, %d]", getClass().getSimpleName(), nsStart, id, value, message, httpStatusCode);
    }
  }

  static String nodeId(ActorSystem<?> actorSystem) {
    String[] ip = actorSystem.address().getHost().orElse("err").split("\\.");
    if (ip.length < 1) {
      throw new RuntimeException(String.format("Akka host (%s) must be a valid IPv4 address."));
    }
    return ip[ip.length - 1];
  }

  static String randomEntityId(String nodeId, int range) {
    return String.format("%s-%s", nodeId, (int) Math.round(Math.random() * range));
  }
}
