package com.angatusistemas.whatsapp.internal;

import com.angatusistemas.whatsapp.model.Account;
import com.angatusistemas.whatsapp.model.BridgeStats;
import com.angatusistemas.whatsapp.model.BusinessProfile;
import com.angatusistemas.whatsapp.model.ButtonReply;
import com.angatusistemas.whatsapp.model.Contact;
import com.angatusistemas.whatsapp.model.ContactStatus;
import com.angatusistemas.whatsapp.model.Group;
import com.angatusistemas.whatsapp.model.GroupCheck;
import com.angatusistemas.whatsapp.model.PollVote;
import com.angatusistemas.whatsapp.model.GroupParticipant;
import com.angatusistemas.whatsapp.model.GroupUpdate;
import com.angatusistemas.whatsapp.model.IncomingCall;
import com.angatusistemas.whatsapp.model.Message;
import com.angatusistemas.whatsapp.model.MessageStatus;
import com.angatusistemas.whatsapp.model.MessageType;
import com.angatusistemas.whatsapp.model.MessageUpdate;
import com.angatusistemas.whatsapp.model.NumberCheck;
import com.angatusistemas.whatsapp.model.ParticipantResult;
import com.angatusistemas.whatsapp.model.Presence;
import com.angatusistemas.whatsapp.model.PresenceUpdate;
import com.angatusistemas.whatsapp.model.QuotedMessage;
import com.angatusistemas.whatsapp.model.Reaction;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the public model records from the bridge's JSON payloads, keeping Gson out of the API.
 *
 * @author Angatu Sistemas
 */
public final class Decode {

    private Decode() {
    }

    public static Message message(String instanceId, JsonObject data) {
        JsonObject quoted = Json.object(data, "quotedMessage");
        JsonObject reply = Json.object(data, "buttonReply");
        return new Message(
                instanceId,
                Json.str(data, "messageId"),
                Json.str(data, "chatId"),
                Json.str(data, "chatIdLid"),
                Json.str(data, "senderId"),
                Json.str(data, "participant"),
                Instant.ofEpochSecond(Json.number(data, "timestamp", Instant.now().getEpochSecond())),
                Json.str(data, "text"),
                MessageType.fromWire(Json.str(data, "type")),
                Json.bool(data, "isGroup", false),
                Json.bool(data, "isFromMe", false),
                Json.str(data, "pushName"),
                quoted == null ? null : new QuotedMessage(Json.str(quoted, "messageId"), Json.str(quoted, "senderId"), Json.str(quoted, "text")),
                reply == null ? null : new ButtonReply(Json.str(reply, "buttonId"), Json.str(reply, "text"), Json.str(reply, "sourceMessageId")),
                Json.bool(data, "hasMedia", false),
                Json.str(data, "mimetype"),
                Json.str(data, "rawJson"));
    }

    public static MessageUpdate messageUpdate(JsonObject data) {
        return new MessageUpdate(Json.str(data, "chatId"), Json.str(data, "messageId"), Json.bool(data, "fromMe", false),
                MessageStatus.fromWire(Json.str(data, "status")), Json.str(data, "editedText"));
    }

    public static Reaction reaction(JsonObject data) {
        return new Reaction(Json.str(data, "chatId"), Json.str(data, "messageId"), Json.str(data, "senderId"),
                Json.str(data, "emoji"), Json.bool(data, "removed", false));
    }

    public static GroupUpdate groupUpdate(JsonObject data) {
        return new GroupUpdate(Json.str(data, "groupJid"), Json.str(data, "action"), Json.strings(data, "participants"),
                Json.str(data, "subject"), Json.str(data, "description"));
    }

    public static PresenceUpdate presence(JsonObject data) {
        long lastSeen = Json.number(data, "lastSeen", 0);
        return new PresenceUpdate(Json.str(data, "chatId"), Json.str(data, "participant"),
                Presence.fromWire(Json.str(data, "presence")), lastSeen > 0 ? Instant.ofEpochSecond(lastSeen) : null);
    }

    public static IncomingCall call(JsonObject data) {
        return new IncomingCall(Json.str(data, "callId"), Json.str(data, "from"), Json.str(data, "chatId"),
                Json.bool(data, "isVideo", false), Json.bool(data, "isGroup", false),
                Instant.ofEpochSecond(Json.number(data, "timestamp", Instant.now().getEpochSecond())));
    }

    public static Account account(JsonObject data) {
        return new Account(Json.str(data, "jid"), Json.str(data, "phoneNumber"), Json.str(data, "name"));
    }

    public static NumberCheck numberCheck(JsonObject data) {
        return new NumberCheck(Json.str(data, "jid"), Json.bool(data, "exists", false), Json.bool(data, "cached", false));
    }

    public static Contact contact(JsonObject data) {
        return new Contact(Json.str(data, "jid"), Json.str(data, "status"), Json.str(data, "profilePictureUrl"),
                Json.bool(data, "onWhatsApp", false));
    }

    public static Group group(JsonObject data) {
        List<GroupParticipant> participants = new ArrayList<>();
        for (JsonElement element : Json.array(data, "participants")) {
            if (element.isJsonObject()) {
                JsonObject participant = element.getAsJsonObject();
                String admin = Json.str(participant, "admin");
                participants.add(new GroupParticipant(Json.str(participant, "id"),
                        "admin".equals(admin) || "superadmin".equals(admin), "superadmin".equals(admin)));
            }
        }
        long creation = Json.number(data, "creation", 0);
        return new Group(Json.str(data, "id"), Json.str(data, "subject"), Json.str(data, "desc"), Json.str(data, "owner"),
                creation > 0 ? Instant.ofEpochSecond(creation) : null,
                Json.bool(data, "announce", false), Json.bool(data, "restrict", false), participants);
    }

    public static List<ParticipantResult> participantResults(JsonObject data) {
        List<ParticipantResult> results = new ArrayList<>();
        for (JsonElement element : Json.array(data, "results")) {
            if (element.isJsonObject()) {
                JsonObject result = element.getAsJsonObject();
                results.add(new ParticipantResult(Json.str(result, "jid"), Json.str(result, "status")));
            }
        }
        return List.copyOf(results);
    }

    public static PollVote pollVote(JsonObject data) {
        return new PollVote(Json.str(data, "chatId"), Json.str(data, "pollMessageId"), Json.str(data, "voterId"),
                Json.strings(data, "selectedOptions"), Instant.ofEpochSecond(Json.number(data, "timestamp", Instant.now().getEpochSecond())));
    }

    public static GroupCheck groupCheck(JsonObject data) {
        return new GroupCheck(Json.str(data, "groupJid"), Json.bool(data, "exists", false), Json.bool(data, "isMember", false),
                Json.str(data, "subject"));
    }

    public static ContactStatus contactStatus(JsonObject data) {
        long setAt = Json.number(data, "setAt", 0);
        return new ContactStatus(Json.str(data, "jid"), Json.str(data, "status"), setAt > 0 ? Instant.ofEpochSecond(setAt) : null);
    }

    public static BusinessProfile businessProfile(JsonObject profile) {
        return new BusinessProfile(Json.str(profile, "wid"), Json.str(profile, "description"), Json.str(profile, "category"),
                Json.str(profile, "email"), Json.str(profile, "address"), Json.strings(profile, "website"));
    }

    public static List<NumberCheck> numberChecks(JsonObject data) {
        List<NumberCheck> results = new ArrayList<>();
        for (JsonElement element : Json.array(data, "results")) {
            if (element.isJsonObject()) {
                results.add(numberCheck(element.getAsJsonObject()));
            }
        }
        return List.copyOf(results);
    }

    public static BridgeStats stats(JsonObject data) {
        return new BridgeStats(Json.number(data, "pid", -1), Json.number(data, "uptimeSeconds", 0), Json.number(data, "rss", 0),
                Json.number(data, "heapUsed", 0), Json.number(data, "heapTotal", 0), Json.number(data, "external", 0),
                Json.array(data, "instances").size(), Json.array(data, "instances").toString());
    }
}
