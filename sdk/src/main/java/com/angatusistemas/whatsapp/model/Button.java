package com.angatusistemas.whatsapp.model;

import java.util.Objects;

/**
 * One reply button: the id the application recognises and the label the contact sees.
 *
 * @author Angatu Sistemas
 */
public record Button(String id, String label) {

    public Button {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (id.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("button id and label must not be blank");
        }
    }

    public static Button of(String id, String label) {
        return new Button(id, label);
    }
}
