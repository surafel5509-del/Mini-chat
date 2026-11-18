package com.minichat.app.navigation

import android.net.Uri

/** Every navigable destination in MINI CHAT. */
object Routes {
    const val WELCOME = "welcome"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT_PASSWORD = "forgot_password"
    const val RESET_PASSWORD = "reset_password?email={email}"
    const val HOME = "home"
    const val CHAT = "chat/{conversationId}"
    const val NEW_GROUP = "group/new"
    const val DISCOVER = "discover"
    const val MY_PROFILE = "profile/me"
    const val USER_PROFILE = "profile/{userId}"
    const val EDIT_PROFILE = "profile/edit"
    const val SETTINGS = "settings"
    const val APPEARANCE = "settings/appearance"
    const val NOTIFICATIONS = "settings/notifications"
    const val PRIVACY = "settings/privacy"
    const val BLOCKED_USERS = "settings/blocked"
    const val ABOUT = "settings/about"
    const val CALL = "call/{callId}"
    const val GROUP_CALL = "group-call/{callId}"
    const val NEW_GROUP_CALL = "group-calls/new?groupId={groupId}"
    const val CALLS = "calls"

    fun chat(conversationId: String): String = "chat/$conversationId"

    fun userProfile(userId: String): String = "profile/$userId"

    fun call(callId: String): String = "call/$callId"

    fun groupCall(callId: String): String = "group-call/$callId"

    /** [groupId] preselects that conversation's members in the group-call composer. */
    fun newGroupCall(groupId: String? = null): String =
        if (groupId.isNullOrBlank()) "group-calls/new" else "group-calls/new?groupId=$groupId"

    fun resetPassword(email: String): String = "reset_password?email=${Uri.encode(email)}"
}
