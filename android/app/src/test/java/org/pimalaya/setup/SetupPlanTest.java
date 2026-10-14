package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;

/**
 * The wizard's decisions over discovered options: which picks share one
 * sign-in, which sending rows go with a reading choice, and which option the
 * standard setup picks. Password and token options cannot be built from
 * here (their methods have no public constructor), so the cases use OAuth
 * code grants and manual entry. Robolectric only because the provider
 * check reads {@link PimalayaClient#googleBase}, a bridge call, and the
 * bridge library loads into the sandbox's class loader.
 */
@RunWith(RobolectricTestRunner.class)
public class SetupPlanTest {
    private static final String EMAIL = "jane@example.org";

    private static final String AUTHORIZE = "https://auth.example.org/authorize";

    private static final String SESSION = "https://api.example.org/jmap/session";

    @Test
    public void oauthPicksBehindOneServerAndAudienceShareOneSignIn() {
        SetupOption mail = option("jmap", SESSION, oauth(AUTHORIZE, "mail"));
        SetupOption calendar = option("caldav", SESSION, oauth(AUTHORIZE, "calendar"));
        SetupOption elsewhere = option("caldav", SESSION, oauth("https://login.other.org/authorize", "calendar"));
        SetupOption otherAudience = option("caldav", "https://dav.example.org/", oauth(AUTHORIZE, "calendar"));

        assertTrue(SetupPlan.sameSignIn(mail, calendar));
        assertFalse(SetupPlan.sameSignIn(mail, elsewhere));
        assertFalse(SetupPlan.sameSignIn(mail, otherAudience));
    }

    @Test
    public void manualEntryNeverShares() {
        SetupOption oauth = option("jmap", SESSION, oauth(AUTHORIZE, "mail"));

        assertFalse(SetupPlan.sameSignIn(manual(), manual()));
        assertFalse(SetupPlan.sameSignIn(manual(), oauth));
        assertFalse(SetupPlan.sameSignIn(oauth, manual()));
    }

    @Test
    public void signInsPoolInMapOrder() {
        Map<PimDomain, SetupOption> picked = new EnumMap<>(PimDomain.class);
        picked.put(PimDomain.CALENDAR, option("caldav", SESSION, oauth(AUTHORIZE, "calendar")));
        picked.put(PimDomain.CONTACTS, manual());
        picked.put(PimDomain.MAIL, option("jmap", SESSION, oauth(AUTHORIZE, "mail")));

        assertEquals(
                List.of(List.of(PimDomain.MAIL, PimDomain.CALENDAR), List.of(PimDomain.CONTACTS)),
                SetupPlan.signIns(picked));
    }

    @Test
    public void scopesSplitOnWhitespaceOnceEachInFirstSeenOrder() {
        Set<String> scopes = new LinkedHashSet<>();
        SetupPlan.addScopes(scopes, " openid  mail");
        SetupPlan.addScopes(scopes, null);
        SetupPlan.addScopes(scopes, "");
        SetupPlan.addScopes(scopes, "mail\tcalendar openid");

        assertEquals(List.of("openid", "mail", "calendar"), new ArrayList<>(scopes));
    }

    @Test
    public void aSendingRowGoesWithWhatMailIsReadThrough() {
        SetupOption graph = option("msgraph", null, oauth(AUTHORIZE, "mail"));
        SetupOption imap = option("imap", null, oauth(AUTHORIZE, "mail"));
        SubmitOption overGraph =
                new SubmitOption("Graph", "graph.microsoft.com", "msgraph://x", false, null, "msgraph", null);
        SubmitOption oauthSmtp = smtp(AuthMethod.Type.OAUTH_AUTHORIZATION_CODE_GRANT);
        SubmitOption none = new SubmitOption("None", null, null, false);

        assertTrue(SetupPlan.offers(graph, overGraph));
        assertFalse(SetupPlan.offers(imap, overGraph));
        assertFalse(SetupPlan.offers(graph, oauthSmtp));
        assertTrue(SetupPlan.offers(imap, oauthSmtp));
        // NOTE: a manual mail server signs in with a password.
        assertFalse(SetupPlan.offers(manual(), oauthSmtp));
        assertTrue(SetupPlan.offers(graph, none));
        assertTrue(SetupPlan.offers(manual(), none));
        assertTrue(SetupPlan.offers(null, none));
    }

    @Test
    public void theFirstOfferedRowSkipsNoneAndManual() {
        SetupOption imap = option("imap", null, oauth(AUTHORIZE, "mail"));
        SubmitOption none = new SubmitOption("None", null, null, false);
        SubmitOption typed = new SubmitOption("Manual", null, null, true);
        SubmitOption oauthSmtp = smtp(AuthMethod.Type.OAUTH_AUTHORIZATION_CODE_GRANT);
        SubmitOption unsigned = smtp(null);
        List<SubmitOption> rows = List.of(none, typed, oauthSmtp, unsigned);

        assertSame(oauthSmtp, SetupPlan.firstOffered(imap, rows));
        assertSame(unsigned, SetupPlan.firstOffered(manual(), rows));
        assertNull(SetupPlan.firstOffered(imap, List.of(none, typed)));
    }

    @Test
    public void theStandardSetupPrefersTheProviderApiThenTheRankedService() {
        AuthMethod google =
                AuthMethod.oauthCodeGrant(
                        Oauth.GOOGLE_AUTH_ENDPOINT, Oauth.GOOGLE_TOKEN_ENDPOINT, Oauth.GOOGLE_PEOPLE_SCOPE);
        SetupOption people =
                new SetupOption(
                        null, "Google", "people.googleapis.com", PimalayaClient.googleBase(EMAIL), null, google, EMAIL);
        SetupOption carddav = option("carddav", null, oauth(AUTHORIZE, "contacts"));
        SetupOption jmap = option("jmap", SESSION, oauth(AUTHORIZE, "contacts"));

        assertTrue(SetupPlan.proprietary(people, EMAIL));
        assertTrue(
                SetupPlan.standardRank(PimDomain.CONTACTS, people, EMAIL)
                        < SetupPlan.standardRank(PimDomain.CONTACTS, jmap, EMAIL));
        assertTrue(
                SetupPlan.standardRank(PimDomain.CONTACTS, jmap, EMAIL)
                        < SetupPlan.standardRank(PimDomain.CONTACTS, carddav, EMAIL));
        assertSame(
                people,
                SetupPlan.standardOption(PimDomain.CONTACTS, List.of(manual(), carddav, people, jmap), EMAIL));
        assertNull(SetupPlan.standardOption(PimDomain.CONTACTS, List.of(manual()), EMAIL));
    }

    @Test
    public void oauthRanksOverTokenOverPassword() {
        assertTrue(
                SetupPlan.authRank(AuthMethod.Type.OAUTH_AUTHORIZATION_CODE_GRANT)
                        < SetupPlan.authRank(AuthMethod.Type.OAUTH_DEVICE_AUTHORIZATION_GRANT));
        assertTrue(
                SetupPlan.authRank(AuthMethod.Type.OAUTH_DEVICE_AUTHORIZATION_GRANT)
                        < SetupPlan.authRank(AuthMethod.Type.BEARER));
        assertTrue(SetupPlan.authRank(AuthMethod.Type.BEARER) < SetupPlan.authRank(AuthMethod.Type.PASSWORD));
    }

    private static AuthMethod oauth(String authorize, String scope) {
        return AuthMethod.oauthCodeGrant(authorize, "https://auth.example.org/token", scope);
    }

    private static SetupOption option(String service, String resource, AuthMethod method) {
        return new SetupOption(service, service, service + ".example.org", null, resource, method, EMAIL);
    }

    private static SetupOption manual() {
        return new SetupOption(null, "Manual", null, null, null, null, null);
    }

    private static SubmitOption smtp(AuthMethod.Type auth) {
        return new SubmitOption(
                "SMTP", "smtp.example.org:465", "smtps://smtp.example.org:465", false, null, null, auth);
    }
}
