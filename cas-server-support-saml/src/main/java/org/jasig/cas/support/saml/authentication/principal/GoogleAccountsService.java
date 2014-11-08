/*
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.support.saml.authentication.principal;

import org.jasig.cas.authentication.principal.AbstractWebApplicationService;
import org.jasig.cas.authentication.principal.Response;
import org.jasig.cas.services.RegisteredService;
import org.jasig.cas.services.ServicesManager;
import org.jasig.cas.support.saml.SamlProtocolConstants;
import org.jasig.cas.support.saml.util.GoogleSaml20ObjectBuilder;
import org.jasig.cas.support.saml.util.Saml20ObjectBuilder;
import org.jasig.cas.util.ISOStandardDateFormat;
import org.jdom.Document;
import org.joda.time.DateTime;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.AuthnContext;
import org.opensaml.saml2.core.AuthnStatement;
import org.opensaml.saml2.core.Conditions;
import org.opensaml.saml2.core.NameID;
import org.opensaml.saml2.core.StatusCode;
import org.opensaml.saml2.core.Subject;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.StringWriter;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of a Service that supports Google Accounts (eventually a more
 * generic SAML2 support will come).
 *
 * @author Scott Battaglia
 * @since 3.1
 */
public class GoogleAccountsService extends AbstractWebApplicationService {

    private static final long serialVersionUID = 6678711809842282833L;

    private final GoogleSaml20ObjectBuilder builder = new GoogleSaml20ObjectBuilder();

    private final String relayState;

    private final PublicKey publicKey;

    private final PrivateKey privateKey;

    private final String requestId;

    private final ServicesManager servicesManager;
    
    /**
     * Instantiates a new google accounts service.
     *
     * @param id the id
     * @param relayState the relay state
     * @param requestId the request id
     * @param privateKey the private key
     * @param publicKey the public key
     * @param servicesManager the services manager
     */
    protected GoogleAccountsService(final String id, final String relayState, final String requestId,
            final PrivateKey privateKey, final PublicKey publicKey, final ServicesManager servicesManager) {
        this(id, id, null, relayState, requestId, privateKey, publicKey, servicesManager);
    }

    /**
     * Instantiates a new google accounts service.
     *
     * @param id the id
     * @param originalUrl the original url
     * @param artifactId the artifact id
     * @param relayState the relay state
     * @param requestId the request id
     * @param privateKey the private key
     * @param publicKey the public key
     * @param servicesManager the services manager
     */
    protected GoogleAccountsService(final String id, final String originalUrl,
            final String artifactId, final String relayState, final String requestId,
            final PrivateKey privateKey, final PublicKey publicKey,
            final ServicesManager servicesManager) {
        super(id, originalUrl, artifactId);
        this.relayState = relayState;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.requestId = requestId;
        this.servicesManager = servicesManager;


    }

    /**
     * Creates the service from request.
     *
     * @param request the request
     * @param privateKey the private key
     * @param publicKey the public key
     * @param servicesManager the services manager
     * @return the google accounts service
     */
    public static GoogleAccountsService createServiceFrom(
            final HttpServletRequest request, final PrivateKey privateKey,
            final PublicKey publicKey, final ServicesManager servicesManager) {
        final String relayState = request.getParameter(SamlProtocolConstants.PARAMETER_SAML_RELAY_STATE);

        final String xmlRequest = Saml20ObjectBuilder.decodeSamlAuthnRequest(
                request.getParameter(SamlProtocolConstants.PARAMETER_SAML_REQUEST));

        if (!StringUtils.hasText(xmlRequest)) {
            return null;
        }

        final Document document = Saml20ObjectBuilder.constructDocumentFromXml(xmlRequest);

        if (document == null) {
            return null;
        }

        final String assertionConsumerServiceUrl =
                document.getRootElement().getAttributeValue("AssertionConsumerServiceURL");
        final String requestId = document.getRootElement().getAttributeValue("ID");

        return new GoogleAccountsService(assertionConsumerServiceUrl,
                relayState, requestId, privateKey, publicKey, servicesManager);
    }

    @Override
    public Response getResponse(final String ticketId) {
        final Map<String, String> parameters = new HashMap<String, String>();
        final String samlResponse = constructSamlResponse();
        final String signedResponse = this.builder.signSamlResponse(samlResponse,
                this.privateKey, this.publicKey);
        parameters.put(SamlProtocolConstants.PARAMETER_SAML_RESPONSE, signedResponse);
        parameters.put(SamlProtocolConstants.PARAMETER_SAML_RELAY_STATE, this.relayState);

        return Response.getPostResponse(getOriginalUrl(), parameters);
    }

    /**
     * Return true if the service is already logged out.
     *
     * @return true if the service is already logged out.
     */
    @Override
    public boolean isLoggedOutAlready() {
        return true;
    }

    /**
     * Construct SAML response.
     * <a href="http://bit.ly/1uI8Ggu">See this reference for more info.</a>
     * @return the SAML response
     */
    private String constructSamlResponse() {
        final DateTime currentDateTime = DateTime.parse(new ISOStandardDateFormat().getCurrentDateAndTime());
        final DateTime NOT_BEFORE_ISSUE_INSTANT = DateTime.parse("2003-04-17T00:46:02Z");

        final RegisteredService svc = this.servicesManager.findServiceBy(this);
        final String userId = svc.getUsernameAttributeProvider().resolveUsername(getPrincipal(), this);

        final org.opensaml.saml2.core.Response response = this.builder.newResponse(
                this.builder.generateSecureRandomId(),
                currentDateTime,
                getId(), this);
        response.setStatus(builder.newStatus(StatusCode.SUCCESS_URI, null));

        final AuthnStatement authnStatement = this.builder.newAuthnStatement(
                AuthnContext.PASSWORD_AUTHN_CTX, currentDateTime);
        final Assertion assertion = this.builder.newAssertion(authnStatement,
                "https://www.opensaml.org/IDP",
                NOT_BEFORE_ISSUE_INSTANT, this.builder.generateSecureRandomId());

        final Conditions conditions = builder.newConditions(NOT_BEFORE_ISSUE_INSTANT,
                currentDateTime, getId());
        assertion.setConditions(conditions);

        final Subject subject = this.builder.newSubject(NameID.EMAIL, userId,
                getId(), currentDateTime, this.requestId);
        assertion.setSubject(subject);

        response.getAssertions().add(assertion);

        final StringWriter writer = new StringWriter();
        this.builder.marshalSamlXmlObject(response, writer);

        logger.debug("Generated Google SAML response: {}", writer.toString());
        return writer.toString();
    }
}
