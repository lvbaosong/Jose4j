package org.jose4j.jwe;

import org.jose4j.base64url.Base64Url;
import org.jose4j.jwa.AlgorithmInfo;
import org.jose4j.keys.AesKey;
import org.jose4j.keys.HmacKey;
import org.jose4j.lang.ByteUtil;
import org.jose4j.lang.JoseException;
import org.jose4j.mac.MacUtil;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;

/**
 */
public class GenericAesCbcHmacSha2ContentEncryptionAlgorithm extends AlgorithmInfo implements ContentEncryptionAlgorithm
{
    private String hmacJavaAlgorithm;
    private int tagTruncationLength;
    private int contentEncryptionKeyByteLength;

    public GenericAesCbcHmacSha2ContentEncryptionAlgorithm()
    {
        setJavaAlgorithm("AES/CBC/PKCS5Padding");
    }

    public int getContentEncryptionKeyByteLength()
    {
        return contentEncryptionKeyByteLength;
    }

    public void setContentEncryptionKeyByteLength(int contentEncryptionKeyByteLength)
    {
        this.contentEncryptionKeyByteLength = contentEncryptionKeyByteLength;
    }

    public String getHmacJavaAlgorithm()
    {
        return hmacJavaAlgorithm;
    }

    public void setHmacJavaAlgorithm(String hmacJavaAlgorithm)
    {
        this.hmacJavaAlgorithm = hmacJavaAlgorithm;
    }

    public int getTagTruncationLength()
    {
        return tagTruncationLength;
    }

    public void setTagTruncationLength(int tagTruncationLength)
    {
        this.tagTruncationLength = tagTruncationLength;
    }

    public ContentEncryptionParts encrypt(byte[] plaintext, byte[] aad, byte[] contentEncryptionKey) throws JoseException
    {
        // The Initialization Vector (IV) used is a 128 bit value generated
        //       randomly or pseudorandomly for use in the cipher.
        byte[] iv = ByteUtil.randomBytes(16);
        return encrypt(plaintext, aad, contentEncryptionKey, iv);
    }

    ContentEncryptionParts encrypt(byte[] plaintext, byte[] aad, byte[] key, byte[] iv) throws JoseException
    {
        Key hmacKey = new HmacKey(ByteUtil.leftHalf(key));
        Key encryptionKey = new AesKey(ByteUtil.rightHalf(key));

        Cipher cipher = CipherUtil.getCipher(getJavaAlgorithm());

        try
        {
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new IvParameterSpec(iv));
        }
        catch (InvalidKeyException e)
        {
            throw new JoseException("Invalid key for " + getJavaAlgorithm(), e);
        }
        catch (InvalidAlgorithmParameterException e)
        {
            throw new JoseException(e.toString(), e);
        }

        byte[] cipherText;
        try
        {
            cipherText = cipher.doFinal(plaintext);
        }
        catch (IllegalBlockSizeException e)
        {
            throw new JoseException(e.toString(), e);
        }
        catch (BadPaddingException e)
        {
            throw new JoseException(e.toString(), e);
        }

        Mac mac = MacUtil.getInitializedMac(getHmacJavaAlgorithm(), hmacKey);

        byte[] al = getAdditionalAuthenticatedDataLengthBytes(aad);

        byte[] authenticationTagInput = ByteUtil.concat(aad, iv, cipherText, al);
        byte[] authenticationTag = mac.doFinal(authenticationTagInput);
        authenticationTag = ByteUtil.subArray(authenticationTag, 0, getTagTruncationLength()); // truncate it

        return new ContentEncryptionParts(iv, cipherText, authenticationTag);
    }


    public byte[] decrypt(ContentEncryptionParts contentEncryptionParts, byte[] aad, byte[] contentEncryptionKey) throws JoseException
    {
        byte[] iv = contentEncryptionParts.getIv();
        byte[] ciphertext = contentEncryptionParts.getCiphertext();
        byte[] authenticationTag = contentEncryptionParts.getAuthenticationTag();
        byte[] al = getAdditionalAuthenticatedDataLengthBytes(aad);
        byte[] authenticationTagInput = ByteUtil.concat(aad, iv, ciphertext, al);
        Key hmacKey = new HmacKey(ByteUtil.leftHalf(contentEncryptionKey));
        Mac mac = MacUtil.getInitializedMac(getHmacJavaAlgorithm(), hmacKey);
        byte[] calculatedAuthenticationTag = mac.doFinal(authenticationTagInput);
        calculatedAuthenticationTag = ByteUtil.subArray(calculatedAuthenticationTag, 0, getTagTruncationLength()); // truncate it
        boolean tagMatch = ByteUtil.secureEquals(authenticationTag, calculatedAuthenticationTag);
        if (!tagMatch)
        {
            Base64Url base64Url = new Base64Url();
            String encTag = base64Url.base64UrlEncode(authenticationTag);
            String calcEncTag = base64Url.base64UrlEncode(calculatedAuthenticationTag);
            throw new JoseException("Authentication tag failed. Message=" + encTag + " calculated=" + calcEncTag);
        }

        Key encryptionKey = new AesKey(ByteUtil.rightHalf(contentEncryptionKey));

        Cipher cipher = CipherUtil.getCipher(getJavaAlgorithm());
        try
        {
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new IvParameterSpec(iv));
        }
        catch (InvalidKeyException e)
        {
            throw new JoseException("Invalid key for " + getJavaAlgorithm(), e);
        }
        catch (InvalidAlgorithmParameterException e)
        {
            throw new JoseException(e.toString(), e);
        }

        try
        {
            return cipher.doFinal(ciphertext);
        }
        catch (IllegalBlockSizeException e)
        {
            throw new JoseException(e.toString(), e);
        }
        catch (BadPaddingException e)
        {
            throw new JoseException(e.toString(), e);
        }
    }

    private byte[] getAdditionalAuthenticatedDataLengthBytes(byte[] additionalAuthenticatedData)
    {
        // The octet string AL is equal to the number of bits in associated data A expressed
        //       as a 64-bit unsigned integer in network byte order.
        long aadLength = ByteUtil.bitLength(additionalAuthenticatedData);
        return ByteUtil.getBytes(aadLength);
    }
}
