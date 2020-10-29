package cluster;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import akka.actor.typed.ActorSystem;

public interface EntityCommand extends CborSerializable {

  public static class ChangeValue implements EntityCommand {
    public final String id;
    public final Object value;
    public final long nsStart;

    @JsonCreator
    public ChangeValue(
      @JsonProperty("id") String id, 
      @JsonProperty("value") Object value, 
      @JsonProperty("nsStart") long nsStart) { 
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
    public ChangeValueAck(
      @JsonProperty("id") String id, 
      @JsonProperty("value") Object value, 
      @JsonProperty("nsStart") long nsStart, 
      @JsonProperty("message") String message, 
      @JsonProperty("httpStatusCode") int httpStatusCode) {
      this.id = id;
      this.value = value;
      this.nsStart = nsStart;
      this.message = message;
      this.httpStatusCode = httpStatusCode;
    }

    @Override
    public String toString() {
      final long responseTime = System.nanoTime() - nsStart;
      return String.format("%s[%,dns, %s, %s, %s, %d]", getClass().getSimpleName(), responseTime, id, value, message, httpStatusCode);
    }
  }

  public static class GetValue implements EntityCommand {
    public final String id;
    public final long nsStart;

    @JsonCreator
    public GetValue(
      @JsonProperty("id") String id, 
      @JsonProperty("nsStart") long nsStart) {
      this.id = id;
      this.nsStart = nsStart;
    }

    @Override
    public String toString() {
      return String.format("%s[%s]", getClass().getSimpleName(), id);
    }
  }

  public static class GetValueAck implements EntityCommand {
    public final String id;
    public final Object value;
    public final long nsStart;
    public final String message;
    public final int httpStatusCode;

    @JsonCreator
    public GetValueAck(
      @JsonProperty("id") String id, 
      @JsonProperty("value") Object value, 
      @JsonProperty("nsStart") long nsStart, 
      @JsonProperty("message") String message, 
      @JsonProperty("httpStatusCode") int httpStatusCode) {
      this.id = id;
      this.value = value;
      this.nsStart = nsStart;
      this.message = message;
      this.httpStatusCode = httpStatusCode;
    }

    @Override
    public String toString() {
      final long responseTime = System.nanoTime() - nsStart;
      return String.format("%s[%,dns, %s, %s, %s, %d]", getClass().getSimpleName(), responseTime, id, value, message, httpStatusCode);
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
