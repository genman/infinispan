package org.infinispan.transaction.xa.recovery;

import net.jcip.annotations.Immutable;
import org.infinispan.marshall.AbstractExternalizer;
import org.infinispan.marshall.Ids;
import org.infinispan.util.Util;

import javax.transaction.xa.Xid;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;
import java.util.Set;

/**
 * This xid implementation is needed because {@link javax.transaction.xa.Xid} is not {@link java.io.Serializable} and
 * we need to serialize it and send it over the network. As the KTA spec does not enforce in anyway the equals and hashcode methods on Xid
 * TM providers are expected to be able to cope with this Xid class when returned from XAResource's methods.
 *
 * @author Mircea.Markus@jboss.com
 * @since 5.0
 */
@Immutable
public class SerializableXid implements Xid {

   private final byte[] branchQualifier;
   private final byte[] globalTransactionId;
   private final int formatId;

   public SerializableXid(byte[] branchQualifier, byte[] globalTransactionId, int formantId) {
      this.branchQualifier = branchQualifier;
      this.globalTransactionId = globalTransactionId;
      this.formatId = formantId;
   }

   public SerializableXid(Xid xid) {
      this(xid.getBranchQualifier(), xid.getGlobalTransactionId(), xid.getFormatId());
   }

   public byte[] getBranchQualifier() {
      return branchQualifier;
   }

   public byte[] getGlobalTransactionId() {
      return globalTransactionId;
   }

   public int getFormatId() {
      return formatId;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      SerializableXid xid = (SerializableXid) o;

      if (formatId != xid.formatId) return false;
      if (!Arrays.equals(branchQualifier, xid.branchQualifier)) return false;
      if (!Arrays.equals(globalTransactionId, xid.globalTransactionId)) return false;

      return true;
   }

   @Override
   public int hashCode() {
      int result = branchQualifier != null ? Arrays.hashCode(branchQualifier) : 0;
      result = 31 * result + (globalTransactionId != null ? Arrays.hashCode(globalTransactionId) : 0);
      result = 31 * result + formatId;
      return result;
   }

   @Override
   public String toString() {
      //todo use a implementation that is more consistent with JBossTM
      return "XidImpl{" +
            "branchQualifier=" + Util.printArray(branchQualifier, false) +
            ", globalTransactionId=" + Util.printArray(globalTransactionId, false) +
            ", formatId=" + formatId +
            '}';
   }

   public static class XidExternalizer extends AbstractExternalizer<SerializableXid> {

      @Override
      public void writeObject(ObjectOutput output, SerializableXid object) throws IOException {
         output.writeObject(object.getBranchQualifier());
         output.writeObject(object.getGlobalTransactionId());
         output.writeInt(object.getFormatId());
      }

      @Override
      public SerializableXid readObject(ObjectInput input) throws IOException, ClassNotFoundException {
         byte[] bq = (byte[]) input.readObject();
         byte[] gtId = (byte[]) input.readObject();
         int type = input.readInt();
         return new SerializableXid(bq, gtId, type);
      }

      @Override
      public Set<Class<? extends SerializableXid>> getTypeClasses() {
         return Util.<Class<? extends SerializableXid>>asSet(SerializableXid.class);
      }

      @Override
      public Integer getId() {
         return Ids.XID;
      }
   }
}
