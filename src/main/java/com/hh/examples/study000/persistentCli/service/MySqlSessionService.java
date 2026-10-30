package com.hh.examples.study000.persistentCli.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.examples.study000.persistentCli.entity.SessionEntity;
import com.hh.examples.study000.persistentCli.entity.SessionEventEntity;
import com.hh.examples.study000.persistentCli.mapper.SessionEventMapper;
import com.hh.examples.study000.persistentCli.mapper.SessionMapper;
import com.hh.examples.study000.persistentCli.support.PersistentCliJson;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MySqlSessionService implements BaseSessionService {

    private final SessionMapper sessionMapper;
    private final SessionEventMapper eventMapper;

    public MySqlSessionService(
            SessionMapper sessionMapper,
            SessionEventMapper eventMapper) {
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Single<Session> createSession(
            String appName,
            String userId,
            ConcurrentMap<String, Object> state,
            String sessionId) {
        return Single.fromCallable(() -> {
            String resolvedSessionId = sessionId == null || sessionId.isBlank()
                    ? UUID.randomUUID().toString()
                    : sessionId;

            SessionEntity existing = findSession(appName, userId, resolvedSessionId);
            if (existing != null) {
                return buildSession(existing, Optional.empty());
            }

            LocalDateTime now = LocalDateTime.now();
            // 使用lombok的builder构建
            SessionEntity entity = SessionEntity.builder()
                    .appName(appName)
                    .userId(userId)
                    .sessionId(resolvedSessionId)
                    .stateJson(PersistentCliJson.toJson(sessionStateSnapshot(state == null ? Map.of() : state)))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            sessionMapper.insert(entity);

            return buildSession(entity, Optional.empty());
        });
    }

    @Override
    public Maybe<Session> getSession(
            String appName,
            String userId,
            String sessionId,
            Optional<GetSessionConfig> configOpt) {
        return Maybe.fromCallable(() -> {
            SessionEntity entity = findSession(appName, userId, sessionId);
            if (entity == null) {
                return null;
            }
            return buildSession(entity, configOpt);
        });
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.fromCallable(() -> {
            List<SessionEntity> entities = sessionMapper.selectList(new LambdaQueryWrapper<SessionEntity>()
                    .eq(SessionEntity::getAppName, appName)
                    .eq(SessionEntity::getUserId, userId)
                    .orderByDesc(SessionEntity::getUpdatedAt));

            List<Session> sessions = entities.stream()
                    .map(entity -> {
                        Session session = buildSession(entity, Optional.empty());
                        // listSessions 只列会话，不携带完整 events，避免一次命令拉出大量轨迹。
                        session.events().clear();
                        return session;
                    })
                    .toList();

            return ListSessionsResponse.builder()
                    .sessions(sessions)
                    .build();
        });
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return Completable.fromAction(() -> {
            sessionMapper.delete(new LambdaQueryWrapper<SessionEntity>()
                    .eq(SessionEntity::getAppName, appName)
                    .eq(SessionEntity::getUserId, userId)
                    .eq(SessionEntity::getSessionId, sessionId));
            eventMapper.delete(new LambdaQueryWrapper<SessionEventEntity>()
                    .eq(SessionEventEntity::getAppName, appName)
                    .eq(SessionEventEntity::getUserId, userId)
                    .eq(SessionEventEntity::getSessionId, sessionId));
        });
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return Single.fromCallable(() -> ListEventsResponse.builder()
                .events(loadEvents(appName, userId, sessionId, Optional.empty()))
                .build());
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        return Single.fromCallable(() -> {
            String appName = session.appName();
            String userId = session.userId();
            String sessionId = session.id();

            // ADK 的 stateDelta 是运行时状态变更；这里必须先合并 state，再保存 session。
            Map<String, Object> stateDelta = event.actions() == null ? Map.of() : event.actions().stateDelta();
            if (stateDelta != null && !stateDelta.isEmpty()) {
                stateDelta.forEach((key, value) -> applyStateDelta(session, key, value));
            }

            persistEvent(appName, userId, sessionId, event);

            SessionEntity entity = findSession(appName, userId, sessionId);
            if (entity != null) {
                entity.setStateJson(PersistentCliJson.toJson(sessionStateSnapshot(session.state())));
                entity.setUpdatedAt(LocalDateTime.now());
                sessionMapper.updateById(entity);
            }

            try {
                session.events().add(event);
            } catch (UnsupportedOperationException ignored) {
                // 某些 Session 快照可能返回不可变列表；数据库已经完成持久化即可。
            }
            return event;
        });
    }

    private Session buildSession(SessionEntity entity, Optional<GetSessionConfig> configOpt) {
        Map<String, Object> state = PersistentCliJson.readMap(entity.getStateJson());

        List<Event> events = loadEvents(
                entity.getAppName(),
                entity.getUserId(),
                entity.getSessionId(),
                configOpt);

        return Session.builder(entity.getSessionId())
                .appName(entity.getAppName())
                .userId(entity.getUserId())
                .state(new ConcurrentHashMap<>(state))
                .events(new ArrayList<>(events))
                .lastUpdateTime(toInstant(entity.getUpdatedAt()))
                .build();
    }

    private List<Event> loadEvents(
            String appName,
            String userId,
            String sessionId,
            Optional<GetSessionConfig> configOpt) {
        List<SessionEventEntity> rows = eventMapper.selectList(new LambdaQueryWrapper<SessionEventEntity>()
                .eq(SessionEventEntity::getAppName, appName)
                .eq(SessionEventEntity::getUserId, userId)
                .eq(SessionEventEntity::getSessionId, sessionId)
                .orderByAsc(SessionEventEntity::getId));

        List<Event> events = rows.stream()
                .filter(row -> !Boolean.TRUE.equals(row.getSnipped()))
                .map(row -> Event.fromJson(row.getEventJson()))
                .map(this::injectEventIdTagForModel)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        GetSessionConfig config = configOpt.orElse(null);
        if (config == null) {
            return events;
        }

        if (config.numRecentEvents().isPresent()) {
            int count = config.numRecentEvents().get();
            if (events.size() <= count) {
                return events;
            }
            return new ArrayList<>(events.subList(events.size() - count, events.size()));
        }

        if (config.afterTimestamp().isPresent()) {
            long afterEpochSecond = config.afterTimestamp().get().getEpochSecond();
            events.removeIf(event -> event.timestamp() <= afterEpochSecond);
        }
        return events;
    }

    private Event injectEventIdTagForModel(Event event) {
        if (event.id() == null || event.id().isBlank() || event.content().isEmpty()) {
            return event;
        }

        Content content = event.content().get();
        String role = content.role().orElse(event.author());
        if (!"user".equals(role)) {
            return event;
        }

        List<Part> parts = new ArrayList<>();
        parts.add(Part.fromText("[event_id:" + event.id() + "]"));
        content.parts().ifPresent(parts::addAll);

        Content taggedContent = Content.builder()
                .role(role)
                .parts(parts)
                .build();
        return event.toBuilder()
                .content(taggedContent)
                .build();
    }

    private void persistEvent(String appName, String userId, String sessionId, Event event) {
        // 某些 ADK 后置事件（例如 compaction 相关事件）可能没有预先填充 eventId。
        // 数据库把 event_id 设计为非空唯一键，因此这里统一补齐，避免插入阶段失败。
        if (event.id() == null || event.id().isBlank()) {
            event.setId(Event.generateEventId());
        }
        if (event.timestamp() <= 0) {
            event.setTimestamp(Instant.now().getEpochSecond());
        }

        SessionEventEntity existing = eventMapper.selectOne(new LambdaQueryWrapper<SessionEventEntity>()
                .eq(SessionEventEntity::getAppName, appName)
                .eq(SessionEventEntity::getUserId, userId)
                .eq(SessionEventEntity::getSessionId, sessionId)
                .eq(SessionEventEntity::getEventId, event.id()));
        if (existing != null) {
            return;
        }

        SessionEventEntity row = SessionEventEntity.builder()
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .eventId(event.id())
                .invocationId(event.invocationId())
                .author(event.author())
                .finalResponse(event.finalResponse())
                .partial(event.partial().orElse(null))
                .compactionEvent(event.actions() != null && event.actions().compaction().isPresent())
                .eventTs(event.timestamp())
                .contentText(eventContentText(event))
                .eventJson(event.toJson())
                .createdAt(LocalDateTime.now())
                .build();
        eventMapper.insert(row);
    }

    private String eventContentText(Event event) {
        String text = event.stringifyContent();
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (event.actions() == null || event.actions().compaction().isEmpty()) {
            return text;
        }
        return event.actions()
                .compaction()
                .map(compaction -> compaction.compactedContent().text())
                .orElse(text);
    }

    private void applyStateDelta(
            Session session,
            String key,
            Object value) {
        // 精简版 SessionService 不再维护 app:/user: 跨 Session state。
        // 所有非 temp: 的 stateDelta 都只作为当前 Session 的 state 保存到 sessions.state_json。
        if (key.startsWith(State.TEMP_PREFIX)) {
            return;
        }
        if (value == State.REMOVED) {
            session.state().remove(key);
        } else {
            session.state().put(key, value);
        }
    }

    private SessionEntity findSession(String appName, String userId, String sessionId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<SessionEntity>()
                .eq(SessionEntity::getAppName, appName)
                .eq(SessionEntity::getUserId, userId)
                .eq(SessionEntity::getSessionId, sessionId));
    }

    private Map<String, Object> sessionStateSnapshot(Map<String, Object> state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        state.forEach((key, value) -> {
            if (!key.startsWith(State.TEMP_PREFIX)) {
                snapshot.put(key, value);
            }
        });
        return snapshot;
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? Instant.now() : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
