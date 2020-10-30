package cluster;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public interface EntityCommand extends CborSerializable {

  public static class ChangeValue implements EntityCommand {
    public final String id;
    public final Object value;
    public final long nsStart;
    public final IpId.Client httpClient;

    @JsonCreator
    public ChangeValue(
      @JsonProperty("id") String id, 
      @JsonProperty("value") Object value, 
      @JsonProperty("nsStart") long nsStart,
      @JsonProperty("httpClient") IpId.Client httpClient) {
      this.id = id;
      this.value = value;
      this.nsStart = nsStart;
      this.httpClient = httpClient;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s, %s]", getClass().getSimpleName(), id, value, httpClient);
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
    public final IpId.Client httpClient;

    @JsonCreator
    public GetValue(
      @JsonProperty("id") String id, 
      @JsonProperty("nsStart") long nsStart,
      @JsonProperty("httpClient") IpId.Client httpClient) {
      this.id = id;
      this.nsStart = nsStart;
      this.httpClient = httpClient;
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %s]", getClass().getSimpleName(), id, httpClient);
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

  static String randomEntityId(String nodeId, int range) {
    return String.format("%s-%s", nodeId, (int) Math.round(Math.random() * range));
  }
}
