package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.ButtonClick;

/**
 * Receives which button a contact chose on a message this account sent, native or text menu alike.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface ButtonListener {

    void onButtonClicked(ButtonClick click);
}
