/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.internal.connection;

import com.mongodb.AuthenticationMechanism;
import com.mongodb.MongoCredential;
import com.mongodb.MongoInternalException;
import com.mongodb.ServerAddress;
import com.mongodb.lang.NonNull;
import com.mongodb.lang.Nullable;
import org.bson.BsonBinary;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.ByteBuf;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import static java.lang.String.format;

public class IamAuthenticator extends SaslAuthenticator {
    private static final int RANDOM_LENGTH = 32;

    public IamAuthenticator(final MongoCredentialWithCache credential) {
        super(credential);
    }

    @Override
    public String getMechanismName() {
        AuthenticationMechanism authMechanism = getMongoCredential().getAuthenticationMechanism();
        if (authMechanism == null) {
            throw new IllegalArgumentException("Authentication mechanism cannot be null");
        }
        return authMechanism.getMechanismName();
    }

    @Override
    protected SaslClient createSaslClient(final ServerAddress serverAddress) {
        return new IamSaslClient(getMongoCredential());
    }

    private static class IamSaslClient implements SaslClient {
        private final MongoCredential credential;
        private int step = -1;
        private byte[] clientNonce = new byte[RANDOM_LENGTH];
        private String httpResponse;

        IamSaslClient(final MongoCredential credential) {
            this.credential = credential;
        }

        @Override
        public String getMechanismName() {
            AuthenticationMechanism authMechanism = credential.getAuthenticationMechanism();
            if (authMechanism == null) {
                throw new IllegalArgumentException("Authentication mechanism cannot be null");
            }
            return authMechanism.getMechanismName();
        }

        @Override
        public boolean hasInitialResponse() {
            return true;
        }

        @Override
        public byte[] evaluateChallenge(final byte[] challenge) throws SaslException {
            step++;
            if (step == 0) {
                return computeClientFirstMessage();
            }
            if (step == 1) {
                return computeClientFinalMessage(challenge);
            } else {
                throw new SaslException(format("Too many steps involved in the %s negotiation.", getMechanismName()));
            }
        }

        @Override
        public boolean isComplete() {
            return step == 1;
        }

        @Override
        public byte[] unwrap(final byte[] bytes, final int i, final int i1) throws SaslException {
            throw new UnsupportedOperationException("Not implemented yet!");
        }

        @Override
        public byte[] wrap(final byte[] bytes, final int i, final int i1) throws SaslException {
            throw new UnsupportedOperationException("Not implemented yet!");
        }

        @Override
        public Object getNegotiatedProperty(final String s) {
            throw new UnsupportedOperationException("Not implemented yet!");
        }

        @Override
        public void dispose() throws SaslException {
            // nothing to do
        }

        private byte[] computeClientFirstMessage() throws SaslException {
            new SecureRandom(this.clientNonce).nextBytes(this.clientNonce);

            BsonDocument document = new BsonDocument()
                    .append("r", new BsonBinary(this.clientNonce))
                    .append("p", new BsonInt32('n'));
            return toBson(document);
        }

        private byte[] computeClientFinalMessage(final byte[] serverFirst) throws SaslException {
            final BsonDocument document = new RawBsonDocument(serverFirst);
            final String host = document.getString("h").getValue();

            final byte[] serverNonce = document.getBinary("s").getData();
            if (serverNonce.length != (2 * RANDOM_LENGTH) || !Arrays.equals(Arrays.copyOf(serverNonce, RANDOM_LENGTH), this.clientNonce)) {
                throw new SaslException("Invalid server nonce");
            }

            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneId.of("UTC"))
                    .format(Instant.now());

            String token = getSessionToken();
            final AuthorizationHeader authorizationHeader = AuthorizationHeader.builder()
                    .setAccessKeyID(getUserName())
                    .setSecretKey(getPassword())
                    .setSessionToken(token)
                    .setHost(host)
                    .setNonce(serverNonce)
                    .setTimestamp(timestamp)
                    .build();

            BsonDocument ret = new BsonDocument()
                    .append("a", new BsonString(authorizationHeader.toString()))
                    .append("d", new BsonString(authorizationHeader.getTimestamp()));
            if (token != null) {
                ret.append("t", new BsonString(token));
            }

            return toBson(ret);
        }


        private byte[] toBson(final BsonDocument document) {
            byte[] bytes;
            BasicOutputBuffer buffer = new BasicOutputBuffer();
            new BsonDocumentCodec().encode(new BsonBinaryWriter(buffer), document, EncoderContext.builder().build());
            bytes = new byte[buffer.size()];
            int curPos = 0;
            for (ByteBuf cur : buffer.getByteBuffers()) {
                System.arraycopy(cur.array(), cur.position(), bytes, curPos, cur.limit());
                curPos += cur.position();
            }
            return bytes;
        }

        @NonNull
        String getUserName() {
            String userName = credential.getUserName();
            if (userName == null) {
                userName = BsonDocument
                        .parse(getHttpResponse())
                        .getString("AccessKeyId")
                        .getValue();
            }
            return userName;
        }

        @NonNull
        private String getPassword() {
            char[] password = credential.getPassword();
            if (password == null) {
                password = BsonDocument
                        .parse(getHttpResponse())
                        .getString("SecretAccessKey")
                        .getValue()
                        .toCharArray();
            }
            return new String(password);
        }

        @Nullable
        private String getSessionToken() {
            String token = credential.getMechanismProperty("AWS_SESSION_TOKEN", null);
            if (credential.getPassword() == null || credential.getUserName() == null) {
                if (token == null) {
                    token = BsonDocument
                            .parse(getHttpResponse())
                            .getString("Token")
                            .getValue();
                } else {
                    throw new IllegalArgumentException("The connection string contains auth properties and no username and password");
                }
            }
            return token;
        }


        @NonNull
        private String getHttpResponse() {
            if (httpResponse == null) {
                final String ec2 = "http://169.254.169.254/latest/meta-data/iam/security-credentials/";
                final String ecs = "http://169.254.170.2";

                String path = System.getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
                String uri = ecs + path;
                if (path == null) {
                    uri = ec2 + /* role name */ getHttpContents(ec2);
                }

                httpResponse = getHttpContents(uri);
            }
            return httpResponse;
        }

        @NonNull
        private static String getHttpContents(final String endpoint) {
            StringBuffer content = new StringBuffer();
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("GET");

                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();
            } catch (IOException e) {
                throw new MongoInternalException("Unexpected IOException", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            return content.toString();
        }
    }

}
