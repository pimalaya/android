package org.pimalaya.mail;

import org.pimalaya.account.SecureStore;
import org.pimalaya.client.Clients;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirDb;

/** What the mail screens work over: the store sides, bridge and accounts. */
public record MailParts(
        PimdirDb pimdir,
        Clients clients,
        MailStore mail,
        FileStore files,
        ItemLinks links,
        SecureStore store) {}
