package org.pimalaya.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One local mutation the io-pimdir engine stages, built by its op's factory.
 *
 * <p>Mirrors rs/offline/wire.rs {@code MutationJson} 584, components in key
 * order, a null one an absent key. The hash is the caller's: the store's
 * content hash ({@code sha256-128}, pimdir SPEC.md §4.3).
 *
 * @param op the operation, the wire's {@code op} tag
 * @param handle the placement's handle; null for an add
 * @param linkId the identity an add files the item under; null otherwise
 * @param target the collection a move or copy goes into; null otherwise
 * @param flags the marker set of a flag set or an add
 * @param hash the body's content hash, for an edit or an add
 * @param size the body's size in bytes, for an edit or an add
 * @param body the body as text, for an edit or a text add
 * @param bodyBase64 the body as base64, for an add of bytes
 * @param summary the derived summary, opaque (the pimdir persistence
 *     format), read-only by contract
 * @param sortKey the derived sort key
 */
public record Mutation(
        Op op,
        String handle,
        String linkId,
        String target,
        Flags flags,
        String hash,
        Integer size,
        String body,
        String bodyBase64,
        JSONObject summary,
        String sortKey) {
    /** The operation of a mutation, as the wire tags it. */
    public enum Op {
        /** A flag set replacing the placement's markers. */
        SET_FLAGS("setFlags"),
        /** A removal of the placement. */
        REMOVE("remove"),
        /** A move to {@code target}: create there, tombstone here (SYNC §3). */
        MOVE("move"),
        /** A copy into {@code target}, the source kept. */
        COPY("copy"),
        /** A content edit of the placement's body. */
        EDIT("edit"),
        /** A locally authored item. */
        ADD("add");

        private final String wire;

        Op(String wire) {
            this.wire = wire;
        }

        /** The value as the bridge spells it. */
        public String wire() {
            return wire;
        }
    }

    /** A flag set on one placement. */
    public static Mutation setFlags(String handle, Flags flags) {
        return new Mutation(Op.SET_FLAGS, handle, null, null, flags, null, null, null, null, null,
                null);
    }

    /** A removal of one placement. */
    public static Mutation remove(String handle) {
        return new Mutation(Op.REMOVE, handle, null, null, null, null, null, null, null, null,
                null);
    }

    /** A move of one placement into {@code target}. */
    public static Mutation move(String handle, String target) {
        return new Mutation(Op.MOVE, handle, null, target, null, null, null, null, null, null,
                null);
    }

    /** A copy of one placement into {@code target}. */
    public static Mutation copy(String handle, String target) {
        return new Mutation(Op.COPY, handle, null, target, null, null, null, null, null, null,
                null);
    }

    /** A content edit on one placement; a null {@code sortKey} keeps it. */
    public static Mutation edit(
            String handle, String hash, String body, JSONObject summary, String sortKey) {
        return new Mutation(
                Op.EDIT,
                handle,
                null,
                null,
                null,
                hash,
                body.getBytes(StandardCharsets.UTF_8).length,
                body,
                null,
                summary,
                sortKey);
    }

    /** A locally authored item, its body text. */
    public static Mutation add(String linkId, Flags flags, String hash, String body,
            JSONObject summary, String sortKey) {
        return new Mutation(
                Op.ADD,
                null,
                linkId,
                null,
                flags,
                hash,
                body.getBytes(StandardCharsets.UTF_8).length,
                body,
                null,
                summary,
                sortKey);
    }

    /**
     * A locally authored item whose body may not be UTF-8 (8-bit mail parts).
     *
     * <p>It crosses in base64 so it is stored as the bytes it is.
     */
    public static Mutation add(String linkId, Flags flags, String hash, byte[] body,
            JSONObject summary, String sortKey) {
        return new Mutation(
                Op.ADD,
                null,
                linkId,
                null,
                flags,
                hash,
                body.length,
                null,
                Base64.getEncoder().encodeToString(body),
                summary,
                sortKey);
    }

    JSONObject toJson() {
        try {
            JSONObject mutation = new JSONObject();
            mutation.put("op", op.wire());
            mutation.put("handle", handle);
            mutation.put("linkId", linkId);
            mutation.put("target", target);
            mutation.put("flags", flags == null ? null : flags.toJson());
            mutation.put("hash", hash);
            mutation.put("size", size);
            mutation.put("body", body);
            mutation.put("bodyBase64", bodyBase64);
            mutation.put("summary", summary);
            // NOTE: an edit changing what the key derives from has to say so,
            // or the item keeps the position its old key gave it.
            mutation.put("sortKey", sortKey);
            return mutation;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable mutation: " + error.getMessage());
        }
    }
}
