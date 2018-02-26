package org.thoughtcrime.securesms.backup;


import android.content.Context;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.annimon.stream.function.Consumer;
import com.annimon.stream.function.Predicate;
import com.google.protobuf.ByteString;

import net.sqlcipher.database.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.crypto.AttachmentSecret;
import org.thoughtcrime.securesms.crypto.ClassicDecryptingPartInputStream;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.ModernDecryptingPartInputStream;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.OneTimePreKeyDatabase;
import org.thoughtcrime.securesms.database.SessionDatabase;
import org.thoughtcrime.securesms.database.SignedPreKeyDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.util.Conversions;
import org.thoughtcrime.securesms.util.Hex;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.kdf.HKDFv3;
import org.whispersystems.libsignal.util.ByteUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class FullBackupExporter extends FullBackupBase {

  @SuppressWarnings("unused")
  private static final String TAG = FullBackupExporter.class.getSimpleName();

  public static void export(@NonNull Context context,
                            @NonNull AttachmentSecret attachmentSecret,
                            @NonNull SQLiteDatabase input,
                            @NonNull File output,
                            @NonNull String passphrase)
      throws IOException
  {
    byte[]                  key          = getBackupKey(passphrase);
    BackupFrameOutputStream outputStream = new BackupFrameOutputStream(output, key);
    outputStream.writeDatabaseVersion(input.getVersion());

    List<String> tables = exportSchema(input, outputStream);
    int          count  = 0;

    for (String table : tables) {
      if (table.equals(SmsDatabase.TABLE_NAME) || table.equals(MmsDatabase.TABLE_NAME)) {
        count = exportTable(table, input, outputStream, cursor -> cursor.getInt(cursor.getColumnIndexOrThrow(MmsSmsColumns.EXPIRES_IN)) <= 0, null, count);
      } else if (table.equals(AttachmentDatabase.TABLE_NAME)) {
        count = exportTable(table, input, outputStream, null, cursor -> exportAttachment(attachmentSecret, cursor, outputStream), count);
      } else if (!table.equals(SignedPreKeyDatabase.TABLE_NAME)  &&
                 !table.equals(OneTimePreKeyDatabase.TABLE_NAME) &&
                 !table.equals(SessionDatabase.TABLE_NAME))
      {
        count = exportTable(table, input, outputStream, null, null, count);
      }
    }

    for (BackupProtos.SharedPreference preference : IdentityKeyUtil.getBackupRecord(context)) {
      if (++count % 100 == 0) EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, count));
      outputStream.write(preference);
    }

    outputStream.writeEnd();
    outputStream.close();
    EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, count));
  }

  private static List<String> exportSchema(@NonNull SQLiteDatabase input, @NonNull BackupFrameOutputStream outputStream)
      throws IOException
  {
    List<String> tables = new LinkedList<>();

    try (Cursor cursor = input.rawQuery("SELECT sql, name, type FROM sqlite_master", null)) {
      while (cursor != null && cursor.moveToNext()) {
        String sql  = cursor.getString(0);
        String name = cursor.getString(1);
        String type = cursor.getString(2);

        if (sql != null) {
          if ("table".equals(type)) {
            outputStream.write(BackupProtos.SqlStatement.newBuilder().setStatement("DROP TABLE IF EXISTS " + name).build());
            tables.add(name);
          } else if ("index".equals(type)) {
            outputStream.write(BackupProtos.SqlStatement.newBuilder().setStatement("DROP INDEX IF EXISTS " + name).build());
          }

          outputStream.write(BackupProtos.SqlStatement.newBuilder().setStatement(cursor.getString(0)).build());
        }
      }
    }

    return tables;
  }

  private static int exportTable(@NonNull   String table,
                                 @NonNull   SQLiteDatabase input,
                                 @NonNull   BackupFrameOutputStream outputStream,
                                 @Nullable  Predicate<Cursor> predicate,
                                 @Nullable  Consumer<Cursor> postProcess,
                                            int count)
      throws IOException
  {
    String template = "INSERT INTO " + table + " VALUES ";

    try (Cursor cursor = input.rawQuery("SELECT * FROM " + table, null)) {
      while (cursor != null && cursor.moveToNext()) {
        if (++count % 100 == 0) EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, count));

        if (predicate == null || predicate.test(cursor)) {
          StringBuilder statement = new StringBuilder(template);

          statement.append('(');

          for (int i=0;i<cursor.getColumnCount();i++) {
            if (cursor.getType(i) == Cursor.FIELD_TYPE_STRING) {
              statement.append('\'');
              statement.append(cursor.getString(i));
              statement.append('\'');
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_FLOAT) {
              statement.append(cursor.getFloat(i));
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER) {
              statement.append(cursor.getLong(i));
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_BLOB) {
              statement.append("x'");
              statement.append(Hex.toStringCondensed(cursor.getBlob(i)));
              statement.append('\'');
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_NULL) {
              statement.append("NULL");
            } else {
              throw new AssertionError("unknown type?"  + cursor.getType(i));
            }

            if (i < cursor.getColumnCount()-1) {
              statement.append(',');
            }
          }

          statement.append(')');

          outputStream.write(BackupProtos.SqlStatement.newBuilder().setStatement(statement.toString()).build());

          if (postProcess != null) postProcess.accept(cursor);
        }
      }
    }

    return count;
  }

  private static void exportAttachment(@NonNull AttachmentSecret attachmentSecret, @NonNull Cursor cursor, @NonNull BackupFrameOutputStream outputStream) {
    try {
      long rowId    = cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.ROW_ID));
      long uniqueId = cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.UNIQUE_ID));
      long size     = cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.SIZE));

      String data   = cursor.getString(cursor.getColumnIndexOrThrow(AttachmentDatabase.DATA));
      byte[] random = cursor.getBlob(cursor.getColumnIndexOrThrow(AttachmentDatabase.DATA_RANDOM));

      if (!TextUtils.isEmpty(data)) {
        InputStream inputStream;

        if (random != null && random.length == 32) inputStream = ModernDecryptingPartInputStream.createFor(attachmentSecret, random, new File(data), 0);
        else                                       inputStream = ClassicDecryptingPartInputStream.createFor(attachmentSecret, new File(data));

        outputStream.write(new AttachmentId(rowId, uniqueId), inputStream, size);
      }
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  private static class BackupFrameOutputStream {

    private final OutputStream outputStream;
    private final Cipher       cipher;
    private final Mac          mac;

    private final byte[]       cipherKey;
    private final byte[]       macKey;

    private byte[] iv;
    private int    counter;

    private BackupFrameOutputStream(@NonNull File output, @NonNull byte[] key) throws IOException {
      try {
        byte[] derived = new HKDFv3().deriveSecrets(key, "Backup Export".getBytes(), 64);
        byte[][] split = ByteUtil.split(derived, 32, 32);

        this.cipherKey = split[0];
        this.macKey    = split[1];

        this.cipher       = Cipher.getInstance("AES/CTR/NoPadding");
        this.mac          = Mac.getInstance("HmacSHA256");
        this.outputStream = new FileOutputStream(output);
        this.iv           = Util.getSecretBytes(16);
        this.counter      = Conversions.byteArrayToInt(iv);

        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));

        byte[] header = BackupProtos.BackupFrame.newBuilder().setHeader(BackupProtos.Header.newBuilder().setIv(ByteString.copyFrom(iv))).build().toByteArray();

        outputStream.write(Conversions.intToByteArray(header.length));
        outputStream.write(header);
      } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
        throw new AssertionError(e);
      }
    }

    public void write(BackupProtos.SharedPreference preference) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder().setPreference(preference).build());
    }

    public void write(BackupProtos.SqlStatement statement) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder().setStatement(statement).build());
    }

    public void write(@NonNull AttachmentId attachmentId, @NonNull InputStream in, long size) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder()
                                                  .setAttachment(BackupProtos.Attachment.newBuilder()
                                                                                        .setRowId(attachmentId.getRowId())
                                                                                        .setAttachmentId(attachmentId.getUniqueId())
                                                                                        .setLength(Util.toIntExact(size))
                                                                                        .build())
                                                  .build());


      try {
        Conversions.intToByteArray(iv, 0, counter++);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));

        byte[] buffer = new byte[8192];
        int read;

        while ((read = in.read(buffer)) != -1) {
          byte[] ciphertext = cipher.update(buffer, 0, read);
          outputStream.write(ciphertext);
          mac.update(ciphertext);
        }

        byte[] remainder = cipher.doFinal();
        outputStream.write(remainder);
        mac.update(remainder);

        byte[] attachmentDigest = mac.doFinal();
        outputStream.write(attachmentDigest, 0, 10);
      } catch (InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
        throw new AssertionError(e);
      }
    }

    void writeDatabaseVersion(int version) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder()
                                                  .setVersion(BackupProtos.DatabaseVersion.newBuilder().setVersion(version))
                                                  .build());
    }

    void writeEnd() throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder().setEnd(true).build());
    }

    private void write(@NonNull OutputStream out, @NonNull BackupProtos.BackupFrame frame) throws IOException {
      try {
        Conversions.intToByteArray(iv, 0, counter++);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));

        byte[] frameCiphertext = cipher.doFinal(frame.toByteArray());
        byte[] frameMac        = mac.doFinal(frameCiphertext);
        byte[] length          = Conversions.intToByteArray(frameCiphertext.length + 10);

        out.write(length);
        out.write(frameCiphertext);
        out.write(frameMac, 0, 10);
      } catch (InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
        throw new AssertionError(e);
      }
    }

    public void close() throws IOException {
      outputStream.close();
    }

  }
}
