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
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.ServiceConfig;
import org.robolectric.RobolectricTestRunner;

/**
 * The wizard's decisions over discovered options: which picks share one
 * sign-in, which sending rows go with a reading choice, and which option the
 * standard setup picks, and the options and grant resources discovered
 * configs give. Robolectric only because the provider
 * check reads {@link AccountClient#googleBase}, a bridge call, and the
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
                AuthMethod.codeGrant(
                        Oauth.GOOGLE_AUTH_ENDPOINT, Oauth.GOOGLE_TOKEN_ENDPOINT, Oauth.GOOGLE_PEOPLE_SCOPE);
        SetupOption people =
                new SetupOption(
                        null, "Google", "people.googleapis.com", AccountClient.googleBase(EMAIL), null, google, EMAIL);
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

    @Test
    public void aGrantAsksForTheResourceTheConfigNames() {
        AuthMethod grant = oauth("https://auth.example.org/oauth/authorize", "mail");
        ServiceConfig http = config("jmap", SESSION, null, "pacc", grant);
        ServiceConfig tcp = config("imap", null, "tls", "pacc", grant);
        ServiceConfig provider = config("gmail", "https://gmail.googleapis.com/", null, "provider:google", grant);

        assertEquals(SESSION, SetupPlan.resourceOf(http, grant));
        assertEquals("https://auth.example.org/", SetupPlan.resourceOf(tcp, grant));
        assertEquals(
                "https://auth.example.org:8443/",
                SetupPlan.resourceOf(tcp, oauth("https://auth.example.org:8443/authorize", "mail")));
        assertNull(SetupPlan.resourceOf(tcp, AuthMethod.password()));
        assertNull(SetupPlan.resourceOf(tcp, null));
        assertNull(SetupPlan.resourceOf(tcp, oauth("not a url", "mail")));
        assertNull(SetupPlan.resourceOf(provider, grant));
        assertTrue(SetupPlan.fromProvider(provider));
        assertFalse(SetupPlan.fromProvider(http));
    }

    @Test
    public void eachDrivableConfigOffersOneOptionPerMethodBestFirst() {
        AuthMethod grant = oauth(AUTHORIZE, "mail");
        AuthMethod issuer = AuthMethod.issuer("https://api.example.org");
        List<ServiceConfig> searched =
                List.of(
                        config("imap", null, "tls", "pacc", AuthMethod.password(), grant),
                        config("imap", null, "starttls", "srv", AuthMethod.password()),
                        config("carddav", "https://dav.example.org/", null, "dav", grant),
                        config("jmap", SESSION, null, "jmap", AuthMethod.bearer(), issuer),
                        config("gmail", "https://gmail.googleapis.com/", null, "provider:google", grant),
                        config("smtp", null, "tls", "pacc", AuthMethod.password()));

        List<SetupOption> options =
                SetupPlan.discoveredOptions(
                        PimDomain.MAIL, searched, EMAIL, EMAIL, service -> "<" + service + ">");

        String jmap = AccountClient.jmapBase(SESSION);
        assertEquals(
                List.of(
                        "jmap|<jmap>|api.example.org|" + jmap + "|" + SESSION + "|" + issuer + "|" + EMAIL,
                        "jmap|<jmap>|api.example.org|" + jmap + "|" + SESSION + "|" + AuthMethod.bearer() + "|" + EMAIL,
                        "imap|<imap>|imap.example.org|imaps://imap.example.org:993|https://auth.example.org/|" + grant + "|jane",
                        "imap|<imap>|imap.example.org|imaps://imap.example.org:993|null|" + AuthMethod.password() + "|jane",
                        "gmail|<gmail>|gmail.googleapis.com|" + AccountClient.googleBase(EMAIL) + "|null|" + grant + "|" + EMAIL),
                describe(options));
        assertEquals(
                2, SetupPlan.discoveredOptions(PimDomain.CALENDAR, searched, EMAIL, EMAIL, service -> service).size());
        assertTrue(
                SetupPlan.discoveredOptions(PimDomain.MAIL, searched.subList(5, 6), EMAIL, EMAIL, service -> service)
                        .isEmpty());
    }

    private static List<String> describe(List<SetupOption> options) {
        List<String> described = new ArrayList<>();
        for (SetupOption option : options) {
            described.add(
                    option.service
                            + "|"
                            + option.label
                            + "|"
                            + option.detail
                            + "|"
                            + option.baseUrl
                            + "|"
                            + option.resource
                            + "|"
                            + option.method
                            + "|"
                            + option.login);
        }
        return described;
    }

    /** A discovered config: an https one when {@code url} is given, else TCP. */
    private static ServiceConfig config(
            String service, String url, String security, String source, AuthMethod... auth) {
        return url != null
                ? new ServiceConfig(service, url, null, 0, null, null, List.of(auth), source)
                : new ServiceConfig(
                        service,
                        null,
                        service + ".example.org",
                        993,
                        security,
                        service.equals("imap") ? "jane" : null,
                        List.of(auth),
                        source);
    }

    private static AuthMethod oauth(String authorize, String scope) {
        return AuthMethod.codeGrant(authorize, "https://auth.example.org/token", scope);
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
