package com.dominik.cardvault.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportUiCapabilitiesTest {
    @Test fun signedOutAndNormalUsersNeverSeeTeamUi() {
        assertFalse(SupportUiCapabilities.fromServerIdentity(null).showTeamNavigation)
        val user = SupportIdentity("user-subject", "Nutzer", SupportRole.USER)
        assertFalse(SupportUiCapabilities.fromServerIdentity(user).showSupportInbox)
        assertFalse(SupportUiCapabilities.fromServerIdentity(user).showAdministration)
    }

    @Test fun supportAgentSeesCasesButNotAdministration() {
        val agent = SupportUiCapabilities.fromServerIdentity(SupportIdentity("agent-subject", null, SupportRole.SUPPORT_AGENT))
        assertTrue(agent.showTeamNavigation)
        assertTrue(agent.showSupportInbox)
        assertTrue(agent.showCaseManagement)
        assertFalse(agent.showAdministration)
    }

    @Test fun adminGetsAllPresentationCapabilities() {
        val admin = SupportUiCapabilities.fromServerIdentity(SupportIdentity("admin-subject", null, SupportRole.ADMIN))
        assertTrue(admin.showTeamNavigation)
        assertTrue(admin.showSupportInbox)
        assertTrue(admin.showCaseManagement)
        assertTrue(admin.showAdministration)
    }
}
