package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.CardCorpus;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.ContactFields;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the phone mapping and the field-model patch on a corpus.
 *
 * <p>Outcomes captured from the replaced JSON model (mapping-corpus.txt): phone
 * rows, merge decisions, patches, conflicted three-way merges. Outputs kept as
 * SHA-256 prefixes, errors by message.
 */
@RunWith(RobolectricTestRunner.class)
public class MappingCorpusTest {
    @Test
    public void everyOutcomeIsTheOneCaptured() throws Exception {
        List<String> expected = new ArrayList<>();
        try (InputStream in = MappingCorpusTest.class.getResourceAsStream("mapping-corpus.txt");
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                expected.add(line);
            }
        }

        List<String> actual = outcomes();
        for (int index = 0; index < Math.min(expected.size(), actual.size()); index++) {
            assertEquals("line " + (index + 1), expected.get(index), actual.get(index));
        }
        assertEquals(expected.size(), actual.size());
    }

    private static List<String> outcomes() throws Exception {
        List<String> vcards = CardCorpus.VCARDS;
        List<String> out = new ArrayList<>();

        List<ContactFields> projected = new ArrayList<>();
        for (int i = 0; i < vcards.size(); i++) {
            try {
                projected.add(ContactClient.projectCard(vcards.get(i)));
                out.add("project " + i + " ok");
            } catch (Exception error) {
                projected.add(null);
                out.add("project " + i + " ERR " + error.getMessage());
            }
        }

        List<List<Map<String, Object>>> phones = new ArrayList<>(CardCorpus.phoneRows());
        for (int i = 0; i < vcards.size(); i++) {
            if (projected.get(i) == null) {
                continue;
            }
            List<Map<String, Object>> rows = Mapping.rows(projected.get(i));
            out.add("rows " + i + " " + hash(rows.toString()));
            phones.add(device(rows));
        }

        for (int i = 0; i < vcards.size(); i++) {
            ContactFields base = projected.get(i);
            if (base == null) {
                continue;
            }
            for (int r = 0; r < phones.size(); r++) {
                ContactFields merged = Mapping.merge(base, Mapping.model(phones.get(r)));
                if (merged == null) {
                    out.add("merge " + i + " " + r + " null");
                    continue;
                }
                StringBuilder taken = new StringBuilder();
                for (Mapping.Field field : Mapping.Field.values()) {
                    if (!Objects.equals(field.of(merged), field.of(base))) {
                        taken.append(taken.length() == 0 ? "" : ",").append(field.key);
                    }
                }
                String vcard = vcards.get(i);
                out.add(
                        "merge " + i + " " + r + " taken=" + taken + " "
                                + result(() -> ContactClient.applyCard(vcard, merged)));
            }
        }

        List<ContactFields> forms = CardCorpus.formFields();
        for (int i = 0; i < vcards.size(); i++) {
            String vcard = vcards.get(i);
            for (int j = 0; j < vcards.size(); j++) {
                ContactFields model = projected.get(j);
                if (model == null) {
                    continue;
                }
                out.add(
                        "apply " + i + " " + j + " "
                                + result(() -> ContactClient.applyCard(vcard, model)));
            }
            for (int k = 0; k < forms.size(); k++) {
                ContactFields form = forms.get(k);
                out.add(
                        "form " + i + " " + k + " "
                                + result(() -> ContactClient.applyCard(vcard, form)));
            }
        }

        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                String base = i == 0 ? "" : vcards.get(i);
                String local = vcards.get(j);
                String remote = vcards.get((i + j + 1) % 6);
                out.add(
                        "conflict " + i + " " + j + " "
                                + result(
                                        () ->
                                                ContactClient.mergeCardChanges(base, local, remote)
                                                        .vcard()));
            }
        }
        return out;
    }

    /** The rows as the provider reads them back: every column a string. */
    private static List<Map<String, Object>> device(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                copy.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            out.add(copy);
        }
        return out;
    }

    private interface Call {
        String run() throws Exception;
    }

    private static String result(Call call) {
        try {
            return hash(call.run());
        } catch (Exception error) {
            return "ERR " + error.getClass().getSimpleName() + " " + error.getMessage();
        }
    }

    private static String hash(String text) throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            out.append(String.format("%02x", digest[index]));
        }
        return out.toString();
    }
}
