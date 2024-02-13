// Copyright 2017 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.internal;

import com.google.crypto.tink.CryptoFormat;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.Key;
import com.google.crypto.tink.Parameters;
import com.google.crypto.tink.monitoring.MonitoringAnnotations;
import com.google.crypto.tink.proto.KeyStatusType;
import com.google.crypto.tink.proto.Keyset;
import com.google.crypto.tink.proto.OutputPrefixType;
import com.google.crypto.tink.subtle.Hex;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * A container class for a set of primitives -- implementations of cryptographic primitives offered
 * by Tink.
 *
 * <p>It provides also additional properties for the primitives it holds. In particular, one of the
 * primitives in the set can be distinguished as "the primary" one.
 *
 * <p>PrimitiveSet is an auxiliary class used for supporting key rotation: primitives in a set
 * correspond to keys in a keyset. Users will usually work with primitive instances, which
 * essentially wrap primitive sets. For example an instance of an Aead-primitive for a given keyset
 * holds a set of Aead-primitives corresponding to the keys in the keyset, and uses the set members
 * to do the actual crypto operations: to encrypt data the primary Aead-primitive from the set is
 * used, and upon decryption the ciphertext's prefix determines the id of the primitive from the
 * set.
 *
 * <p>PrimitiveSet is a public class to allow its use in implementations of custom primitives.
 *
 * @since 1.0.0
 */
public final class PrimitiveSet<P> {

  /**
   * A single entry in the set. In addition to the actual primitive it holds also some extra
   * information about the primitive.
   */
  public static final class Entry<P> {
    // If set, this is a primitive of a key.
    @Nullable private final P fullPrimitive;
    @Nullable private final P primitive;
    // Identifies the primitive within the set.
    // It is the ciphertext prefix of the corresponding key.
    private final byte[] identifier;
    // The status of the key represented by the primitive.
    private final KeyStatusType status;
    // The output prefix type of the key represented by the primitive.
    private final OutputPrefixType outputPrefixType;
    // The id of the key.
    private final int keyId;
    private final String keyType;
    private final Key key;

    Entry(
        @Nullable P fullPrimitive,
        @Nullable P primitive,
        final byte[] identifier,
        KeyStatusType status,
        OutputPrefixType outputPrefixType,
        int keyId,
        String keyType,
        Key key) {
      this.fullPrimitive = fullPrimitive;
      this.primitive = primitive;
      this.identifier = Arrays.copyOf(identifier, identifier.length);
      this.status = status;
      this.outputPrefixType = outputPrefixType;
      this.keyId = keyId;
      this.keyType = keyType;
      this.key = key;
    }

    /**
     * Returns the full primitive for this entry.
     *
     * <p>This is used in cases when the new Tink Key interface is used and the primitive is
     * self-sufficient by itself, meaning that all the necessary information to process the
     * primitive is contained in the primitive (most likely through the new Key interface), as
     * opposed to the {@code primitive} field (see {@link #getPrimitive} for details).
     */
    @Nullable
    public P getFullPrimitive() {
      return this.fullPrimitive;
    }

    /**
     * Returns the primitive for this entry.
     *
     * <p>For primitives of type {@code Mac}, {@code Aead}, {@code PublicKeySign}, {@code
     * PublicKeyVerify}, {@code DeterministicAead}, {@code HybridEncrypt}, and {@code HybridDecrypt}
     * this is a primitive which <b>ignores</b> the output prefix and assumes "RAW".
     */
    @Nullable
    public P getPrimitive() {
      return this.primitive;
    }

    public KeyStatusType getStatus() {
      return status;
    }

    public OutputPrefixType getOutputPrefixType() {
      return outputPrefixType;
    }

    @Nullable
    public final byte[] getIdentifier() {
      if (identifier == null) {
        return null;
      } else {
        return Arrays.copyOf(identifier, identifier.length);
      }
    }

    public int getKeyId() {
      return keyId;
    }

    public String getKeyType() {
      return keyType;
    }

    public Key getKey() {
      return key;
    }

    @Nullable
    public Parameters getParameters() {
      if (key == null) {
        return null;
      }
      return key.getParameters();
    }
  }

  private static <P> Entry<P> createEntry(
      @Nullable P fullPrimitive, @Nullable P primitive, Keyset.Key key)
      throws GeneralSecurityException {
    @Nullable Integer idRequirement = key.getKeyId();
    if (key.getOutputPrefixType() == OutputPrefixType.RAW) {
      idRequirement = null;
    }
    Key keyObject =
        MutableSerializationRegistry.globalInstance()
            .parseKeyWithLegacyFallback(
                ProtoKeySerialization.create(
                    key.getKeyData().getTypeUrl(),
                    key.getKeyData().getValue(),
                    key.getKeyData().getKeyMaterialType(),
                    key.getOutputPrefixType(),
                    idRequirement),
                InsecureSecretKeyAccess.get());
    return new Entry<P>(
        fullPrimitive,
        primitive,
        CryptoFormat.getOutputPrefix(key),
        key.getStatus(),
        key.getOutputPrefixType(),
        key.getKeyId(),
        key.getKeyData().getTypeUrl(),
        keyObject);
  }

  private static <P> void storeEntryInPrimitiveSet(
      Entry<P> entry,
      ConcurrentMap<Prefix, List<Entry<P>>> primitives,
      List<Entry<P>> primitivesInKeysetOrder) {
    List<Entry<P>> list = new ArrayList<>();
    list.add(entry);
    // Cannot use byte[] as keys in hash map, convert to Prefix wrapper class.
    Prefix identifier = new Prefix(entry.getIdentifier());
    List<Entry<P>> existing = primitives.put(identifier, Collections.unmodifiableList(list));
    if (existing != null) {
      List<Entry<P>> newList = new ArrayList<>();
      newList.addAll(existing);
      newList.add(entry);
      primitives.put(identifier, Collections.unmodifiableList(newList));
    }
    primitivesInKeysetOrder.add(entry);
  }

  /** Returns the entry with the primary primitive. */
  @Nullable
  public Entry<P> getPrimary() {
    return primary;
  }

  public boolean hasAnnotations() {
    return !annotations.toMap().isEmpty();
  }

  public MonitoringAnnotations getAnnotations() {
    return annotations;
  }

  /** Returns all primitives using RAW prefix. */
  public List<Entry<P>> getRawPrimitives() {
    return getPrimitive(CryptoFormat.RAW_PREFIX);
  }

  /** Returns the entries with primitive identifed by {@code identifier}. */
  public List<Entry<P>> getPrimitive(final byte[] identifier) {
    List<Entry<P>> found = primitives.get(new Prefix(identifier));
    return found != null ? found : Collections.<Entry<P>>emptyList();
  }

  /** Returns all primitives. */
  public Collection<List<Entry<P>>> getAll() {
    return primitives.values();
  }

  /** Returns all primitives in the original keyset key order. */
  public List<Entry<P>> getAllInKeysetOrder() {
    return Collections.unmodifiableList(primitivesInKeysetOrder);
  }

  /**
   * The primitives are stored in a hash map of (ciphertext prefix, list of primitives sharing the
   * prefix). This allows quickly retrieving the list of primitives sharing some particular prefix.
   * Because all RAW keys are using an empty prefix, this also quickly allows retrieving them.
   */
  private final ConcurrentMap<Prefix, List<Entry<P>>> primitives;

  /** Stores entries in the original keyset key order. */
  private final List<Entry<P>> primitivesInKeysetOrder;

  private final Entry<P> primary;
  private final Class<P> primitiveClass;
  private final MonitoringAnnotations annotations;

  /** Creates an immutable PrimitiveSet. It is used by the Builder. */
  private PrimitiveSet(
      ConcurrentMap<Prefix, List<Entry<P>>> primitives,
      List<Entry<P>> primitivesInKeysetOrder,
      Entry<P> primary,
      MonitoringAnnotations annotations,
      Class<P> primitiveClass) {
    this.primitives = primitives;
    this.primitivesInKeysetOrder = primitivesInKeysetOrder;
    this.primary = primary;
    this.primitiveClass = primitiveClass;
    this.annotations = annotations;
  }

  public Class<P> getPrimitiveClass() {
    return primitiveClass;
  }

  private static class Prefix implements Comparable<Prefix> {
    private final byte[] prefix;

    private Prefix(byte[] prefix) {
      this.prefix = Arrays.copyOf(prefix, prefix.length);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(prefix);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Prefix)) {
        return false;
      }
      Prefix other = (Prefix) o;
      return Arrays.equals(prefix, other.prefix);
    }

    @Override
    public int compareTo(Prefix o) {
      if (prefix.length != o.prefix.length) {
        return prefix.length - o.prefix.length;
      }
      for (int i = 0; i < prefix.length; i++) {
        if (prefix[i] != o.prefix[i]) {
          return prefix[i] - o.prefix[i];
        }
      }
      return 0;
    }

    @Override
    public String toString() {
      return Hex.encode(prefix);
    }
  }

  /** Builds an immutable PrimitiveSet. This is the prefered way to construct a PrimitiveSet. */
  public static class Builder<P> {
    private final Class<P> primitiveClass;

    // primitives == null indicates that build has been called and the builder can't be used
    // anymore.
    private ConcurrentMap<Prefix, List<Entry<P>>> primitives = new ConcurrentHashMap<>();
    private final List<Entry<P>> primitivesInKeysetOrder = new ArrayList<>();
    private Entry<P> primary;
    private MonitoringAnnotations annotations;

    @CanIgnoreReturnValue
    private Builder<P> addPrimitive(
        @Nullable final P fullPrimitive,
        @Nullable final P primitive,
        Keyset.Key key,
        boolean asPrimary)
        throws GeneralSecurityException {
      if (primitives == null) {
        throw new IllegalStateException("addPrimitive cannot be called after build");
      }
      if (fullPrimitive == null && primitive == null) {
        throw new GeneralSecurityException(
            "at least one of the `fullPrimitive` or `primitive` must be set");
      }
      if (key.getStatus() != KeyStatusType.ENABLED) {
        throw new GeneralSecurityException("only ENABLED key is allowed");
      }
      Entry<P> entry = createEntry(fullPrimitive, primitive, key);
      storeEntryInPrimitiveSet(entry, primitives, primitivesInKeysetOrder);
      if (asPrimary) {
        if (this.primary != null) {
          throw new IllegalStateException("you cannot set two primary primitives");
        }
        this.primary = entry;
      }
      return this;
    }

    /* Adds a non-primary primitive.*/
    @CanIgnoreReturnValue
    public Builder<P> addPrimitive(final P primitive, Keyset.Key key)
        throws GeneralSecurityException {
      return addPrimitive(null, primitive, key, false);
    }

    /**
     * Adds the primary primitive. This or addPrimaryFullPrimitiveAndOptionalPrimitive should be
     * called exactly once per PrimitiveSet.
     */
    @CanIgnoreReturnValue
    public Builder<P> addPrimaryPrimitive(final P primitive, Keyset.Key key)
        throws GeneralSecurityException {
      return addPrimitive(null, primitive, key, true);
    }

    @CanIgnoreReturnValue
    public Builder<P> addFullPrimitiveAndOptionalPrimitive(
        @Nullable final P fullPrimitive, @Nullable final P primitive, Keyset.Key key)
        throws GeneralSecurityException {
      return addPrimitive(fullPrimitive, primitive, key, false);
    }

    /**
     * Adds the primary primitive and full primitive. This or addPrimaryPrimitive should be called
     * exactly once per PrimitiveSet.
     */
    @CanIgnoreReturnValue
    public Builder<P> addPrimaryFullPrimitiveAndOptionalPrimitive(
        @Nullable final P fullPrimitive, @Nullable final P primitive, Keyset.Key key)
        throws GeneralSecurityException {
      return addPrimitive(fullPrimitive, primitive, key, true);
    }

    @CanIgnoreReturnValue
    public Builder<P> setAnnotations(MonitoringAnnotations annotations) {
      if (primitives == null) {
        throw new IllegalStateException("setAnnotations cannot be called after build");
      }
      this.annotations = annotations;
      return this;
    }

    public PrimitiveSet<P> build() throws GeneralSecurityException {
      if (primitives == null) {
        throw new IllegalStateException("build cannot be called twice");
      }
      // Note that we currently don't enforce that primary must be set.
      PrimitiveSet<P> output =
          new PrimitiveSet<P>(
              primitives, primitivesInKeysetOrder, primary, annotations, primitiveClass);
      this.primitives = null;
      return output;
    }

    private Builder(Class<P> primitiveClass) {
      this.primitiveClass = primitiveClass;
      this.annotations = MonitoringAnnotations.EMPTY;
    }
  }

  public static <P> Builder<P> newBuilder(Class<P> primitiveClass) {
    return new Builder<P>(primitiveClass);
  }
}
