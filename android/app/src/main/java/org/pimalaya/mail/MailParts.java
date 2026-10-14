package org.pimalaya.mail;

import org.pimalaya.account.SecureStore;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirDb;

/**
 * What the mail screens work over: the store, the bridge, the mail, file
 * and link sides of the store, and the accounts kept on the device.
 */
public record MailParts(
        PimdirDb pimdir,
        PimalayaClient client,
        MailStore mail,
        FileStore files,
        ItemLinks links,
        SecureStore store) {}
