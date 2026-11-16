package com.angatusistemas.whatsapp.queue;

import br.com.angatusistemas.lib.database.Saveable;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * A text-style button menu sent to a chat, kept until it is answered or expires, so a reply such as
 * "2" or the label itself can be traced back to the button it chose.
 *
 * @author Angatu Sistemas
 */
@Getter
@Setter
public final class ButtonMenu extends Saveable {

    private String id;
    private String instanceId;
    private String chatId;
    private List<String> buttonIds = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private long expiresAt;
}
