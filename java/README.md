# kakaotalk-bot (Java)

KakaoTalk Bot API client for Java 11+: REST API wrapper, Gateway (WebSocket) client and webhook helpers.
Built on `java.net.http.HttpClient` / `java.net.http.WebSocket`; the only dependency is Jackson.

```xml
<dependency>
  <groupId>com.kakaotalkbot</groupId>
  <artifactId>kakaotalk-bot</artifactId>
  <version>0.1.0</version>
</dependency>
```

## REST API

```java
import com.kakaotalkbot.*;
import com.kakaotalkbot.model.*;

KakaoBotClient client = KakaoBotClient.builder(System.getenv("BOT_TOKEN"))
        .timeout(Duration.ofSeconds(10))
        .build();

client.sendCallback(callbackToken, SkillResponse.text("처리가 완료되었습니다."));
client.sendToGroup(botGroupKey, SkillResponse.text("안녕하세요!"));
client.sendToUser(botUserKey, SkillResponse.text("안녕하세요!"));   // channel chat only

CommandsResponse commands = client.getCommands();
client.updateCommands(List.of(new UtteranceRequest("도움말"), new UtteranceRequest("상담원 연결", "상담원과 연결합니다.")));
Guide guide = client.updateGuide("🤖 저와 함께 이런 것들을 할 수 있어요.");

SkillResponse response = SkillResponse.builder()
        .text("안녕하세요 " + Components.mentionTag("u1") + "!")
        .mention("u1", botUserKey)
        .add(Components.basicCard("제목", "설명", Components.thumbnail("https://example.com/a.png"),
                List.of(Buttons.webLink("열기", "https://example.com"), Buttons.message("다시", "다시")), null))
        .quickReply("도움말")
        .build();

try {
    client.sendCallback(callbackToken, response);
} catch (KakaoBotApiException e) {
    if (e.isCallbackTokenError()) { /* -881 */ }
}

// Every call also has an ...Async variant returning CompletableFuture
client.sendCallbackAsync(callbackToken, response).thenAccept(r -> System.out.println(r.getSendCount()));
```

## Gateway (WebSocket)

```java
KakaoBotGateway gateway = KakaoBotGateway.builder(System.getenv("BOT_TOKEN"))
        // .url(...).client(client).autoReconnect(true).initialBackoff(Duration.ofSeconds(1))
        // .maxBackoff(Duration.ofSeconds(30)).heartbeatMargin(Duration.ofSeconds(5)).handshakeTimeout(Duration.ofSeconds(15))
        .listener(new GatewayListener() {
            @Override public void onReady(ReadyData ready) {
                System.out.println("bot " + ready.getBot().getId() + " session " + ready.getSessionId());
            }
            @Override public void onMessageCreate(DispatchEvent event) {
                MessageCreateData data = event.asMessageCreate();   // id is a long
                if (event.canReply()) event.replyText("echo: " + data.getContent());
            }
            @Override public void onReconnecting(int attempt, Duration delay, boolean willResume) {
                System.out.println("reconnecting #" + attempt + " in " + delay + " resume=" + willResume);
            }
            @Override public void onError(Throwable error) {
                error.printStackTrace();   // KakaoBotGatewayException.isFatal() for 4001/4003/4004
            }
        })
        .build();

gateway.connect();          // blocks until READY (or use connectAsync())
// ...
gateway.close();            // stops reconnecting and shuts down owned threads
```

Listener callbacks run on a single-threaded event executor by default (ordered delivery);
pass `.eventExecutor(...)` for parallel handling. Save `gateway.getSession()` and call
`gateway.restoreSession(sessionId, lastSeq)` before `connect()` to RESUME across process restarts.

## Webhook

```java
// e.g. in a Spring @RestController / servlet
@PostMapping("/webhook")
public ResponseEntity<Void> webhook(@RequestBody byte[] body, @RequestHeader("X-My-Secret") String secret) {
    if (!Webhook.verifyCustomHeader(secret, WEBHOOK_SECRET)) return ResponseEntity.status(401).build();
    DispatchEvent event = Webhook.parse(body, client);
    if (event.getType() == EventType.MESSAGE_CREATE && event.canReply()) {
        event.replyTextAsync("echo: " + event.getContent());   // after responding 2xx within 1 second
    }
    return ResponseEntity.noContent().build();
}
```

## Build

```bash
mvn test     # unit tests + gateway/REST integration tests against in-process mock servers
mvn package
```

The integration tests use `org.java-websocket:Java-WebSocket` (test scope only) as a mock gateway
and the JDK's built-in `com.sun.net.httpserver.HttpServer` as a mock REST API.
