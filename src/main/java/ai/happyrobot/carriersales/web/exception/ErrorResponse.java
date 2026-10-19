package ai.happyrobot.carriersales.web.exception;

// Every API error has this shape. message is a short sentence the voice agent can say as is.
public record ErrorResponse(String error, String message) {
}
