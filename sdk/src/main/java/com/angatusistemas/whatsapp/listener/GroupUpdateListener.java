package com.angatusistemas.whatsapp.listener;

import com.angatusistemas.whatsapp.model.GroupUpdate;

/**
 * Receives member and metadata changes of groups the account belongs to.
 *
 * @author Angatu Sistemas
 */
@FunctionalInterface
public interface GroupUpdateListener {

    void onGroupUpdated(GroupUpdate update);
}
