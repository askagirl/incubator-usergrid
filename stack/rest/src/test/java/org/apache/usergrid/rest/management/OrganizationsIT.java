package org.apache.usergrid.rest.management;


import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.MediaType;

import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.usergrid.management.ApplicationInfo;
import org.apache.usergrid.management.OrganizationInfo;
import org.apache.usergrid.management.UserInfo;
import org.apache.usergrid.persistence.EntityManager;
import org.apache.usergrid.persistence.entities.User;
import org.apache.usergrid.rest.AbstractRestIT;
import org.apache.usergrid.rest.TestContextSetup;
import org.apache.usergrid.rest.management.organizations.OrganizationsResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.representation.Form;

import junit.framework.Assert;

import static junit.framework.Assert.fail;
import static org.apache.usergrid.management.AccountCreationProps.PROPERTIES_ADMIN_USERS_REQUIRE_CONFIRMATION;
import static org.apache.usergrid.management.AccountCreationProps.PROPERTIES_SYSADMIN_APPROVES_ADMIN_USERS;
import static org.apache.usergrid.management.AccountCreationProps.PROPERTIES_SYSADMIN_APPROVES_ORGANIZATIONS;
import static org.apache.usergrid.management.AccountCreationProps.PROPERTIES_SYSADMIN_EMAIL;
import static org.apache.usergrid.utils.MapUtils.hashMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;


/**
 * Created by ApigeeCorporation on 9/17/14.
 */
public class OrganizationsIT extends AbstractRestIT {
    private static final Logger LOG = LoggerFactory.getLogger( OrganizationsIT.class );

    @Test
    public void createOrgAndOwner() throws Exception {

        Map<String, String> originalProperties = getRemoteTestProperties();

        try {
            setTestProperty( PROPERTIES_SYSADMIN_APPROVES_ADMIN_USERS, "false" );
            setTestProperty( PROPERTIES_SYSADMIN_APPROVES_ORGANIZATIONS, "false" );
            setTestProperty( PROPERTIES_ADMIN_USERS_REQUIRE_CONFIRMATION, "false" );
            setTestProperty( PROPERTIES_SYSADMIN_EMAIL, "sysadmin-1@mockserver.com" );

            Map<String, Object> organizationProperties = new HashMap<String, Object>();
            organizationProperties.put( "securityLevel", 5 );

            Map payload = hashMap( "email", "test-user-1@mockserver.com" ).map( "username", "test-user-1" )
                                                                          .map( "name", "Test User" ).map( "password", "password" ).map( "organization", "test-org-1" )
                                                                          .map( "company", "Apigee" );
            payload.put( OrganizationsResource.ORGANIZATION_PROPERTIES, organizationProperties );

            JsonNode node = mapper.readTree( resource().path( "/management/organizations" ).accept( MediaType.APPLICATION_JSON )
                                                       .type( MediaType.APPLICATION_JSON_TYPE ).post( String.class, payload ));

            assertNotNull( node );

            ApplicationInfo applicationInfo = setup.getMgmtSvc().getApplicationInfo( "test-org-1/sandbox" );

            assertNotNull( applicationInfo );

            Set<String> rolePerms =
                    setup.getEmf().getEntityManager( applicationInfo.getId() ).getRolePermissions( "guest" );
            assertNotNull( rolePerms );
            assertTrue( rolePerms.contains( "get,post,put,delete:/**" ) );
            logNode( node );

            UserInfo ui = setup.getMgmtSvc().getAdminUserByEmail( "test-user-1@mockserver.com" );
            EntityManager em = setup.getEmf().getEntityManager( setup.getEmf().getManagementAppId() );
            User user = em.get( ui.getUuid(), User.class );
            assertEquals( "Test User", user.getName() );
            assertEquals( "Apigee", user.getProperty( "company" ));

            OrganizationInfo orgInfo = setup.getMgmtSvc().getOrganizationByName( "test-org-1" );
            assertEquals( 5L, orgInfo.getProperties().get( "securityLevel" ) );

            node = mapper.readTree( resource().path( "/management/organizations/test-org-1" )
                                              .queryParam( "access_token", superAdminToken() ).accept( MediaType.APPLICATION_JSON )
                                              .type( MediaType.APPLICATION_JSON_TYPE ).get( String.class ));
            logNode( node );
            Assert.assertEquals( 5, node.get( "organization" ).get( OrganizationsResource.ORGANIZATION_PROPERTIES )
                                        .get( "securityLevel" ).asInt() );

            node = mapper.readTree( resource().path( "/management/organizations/test-org-1" )
                                              .queryParam( "access_token", superAdminToken() ).accept( MediaType.APPLICATION_JSON )
                                              .type( MediaType.APPLICATION_JSON_TYPE ).get( String.class ));
            Assert.assertEquals( 5, node.get( "organization" ).get( OrganizationsResource.ORGANIZATION_PROPERTIES )
                                        .get( "securityLevel" ).asInt() );
        }
        finally {
            setTestProperties( originalProperties );
        }
    }


    @Test
    public void testCreateDuplicateOrgName() throws Exception {

        // create organization with name
        Map<String, String> payload =
                hashMap( "email", "create-duplicate-org@mockserver.com" )
                        .map( "password", "password" )
                        .map( "organization", "create-duplicate-orgname-org" );
        JsonNode node = mapper.readTree( resource().path( "/management/organizations" ).accept( MediaType.APPLICATION_JSON )
                                                   .type( MediaType.APPLICATION_JSON_TYPE ).post( String.class, payload ));

        logNode( node );
        assertNotNull( node );

        refreshIndex("create-duplicate-orgname-org", "dummy");

        // create another org with that same name, but a different user
        payload = hashMap( "email", "create-duplicate-org2@mockserver.com" )
                .map( "username", "create-dupe-orgname2" )
                .map( "password", "password" )
                .map( "organization", "create-duplicate-orgname-org" );
        try {
            node = mapper.readTree( resource().path( "/management/organizations" ).accept( MediaType.APPLICATION_JSON )
                                              .type( MediaType.APPLICATION_JSON_TYPE ).post( String.class, payload ));
        }
        catch ( Exception ex ) {
        }

        refreshIndex("create-duplicate-orgname-org", "dummy");

        // now attempt to login as the user for the second organization
        payload = hashMap( "grant_type", "password" )
                .map( "username", "create-dupe-orgname2" )
                .map( "password", "password" );
        try {
            node = mapper.readTree( resource().path( "/management/token" ).accept( MediaType.APPLICATION_JSON )
                                              .type( MediaType.APPLICATION_JSON_TYPE ).post( String.class, payload ));
            fail( "Should not have created user" );
        }
        catch ( Exception ex ) {
        }
        logNode( node );

        refreshIndex("create-duplicate-orgname-org", "dummy");

        payload = hashMap( "username", "create-duplicate-org@mockserver.com" )
                .map( "grant_type", "password" )
                .map( "password", "password" );
        node = mapper.readTree( resource().path( "/management/token" ).accept( MediaType.APPLICATION_JSON )
                                          .type( MediaType.APPLICATION_JSON_TYPE ).post( String.class, payload ));
        logNode( node );
    }

    @Test
    public void testCreateDuplicateOrgEmail() throws Exception {

        Map<String, String> payload =
                hashMap( "email", "duplicate-email@mockserver.com" )
                        .map( "password", "password" )
                        .map( "organization", "very-nice-org" );

        JsonNode node = mapper.readTree( resource().path( "/management/organizations" )
                                                   .accept( MediaType.APPLICATION_JSON )
                                                   .type( MediaType.APPLICATION_JSON_TYPE )
                                                   .post( String.class, payload ));

        logNode( node );
        assertNotNull( node );

        payload = hashMap( "email", "duplicate-email@mockserver.com" )
                .map( "username", "anotheruser" )
                .map( "password", "password" )
                .map( "organization", "not-so-nice-org" );

        boolean failed = false;
        try {
            node = mapper.readTree( resource().path( "/management/organizations" )
                                              .accept( MediaType.APPLICATION_JSON )
                                              .type( MediaType.APPLICATION_JSON_TYPE )
                                              .post( String.class, payload ));
        }
        catch ( UniformInterfaceException ex ) {
            Assert.assertEquals( 400, ex.getResponse().getStatus() );
            JsonNode errorJson = ex.getResponse().getEntity( JsonNode.class );
            Assert.assertEquals( "duplicate_unique_property_exists", errorJson.get("error").asText());
            failed = true;
        }
        Assert.assertTrue(failed);

        refreshIndex("test-organization", "test-app");

        payload = hashMap( "grant_type", "password" )
                .map( "username", "create-dupe-orgname2" )
                .map( "password", "password" );
        try {
            node = mapper.readTree( resource().path( "/management/token" )
                                              .accept( MediaType.APPLICATION_JSON )
                                              .type( MediaType.APPLICATION_JSON_TYPE )
                                              .post( String.class, payload ));
            fail( "Should not have created user" );
        }
        catch ( Exception ex ) {
        }

        logNode( node );

        refreshIndex("test-organization", "test-app");

        payload = hashMap( "username", "duplicate-email@mockserver.com" )
                .map( "grant_type", "password" )
                .map( "password", "password" );
        node = mapper.readTree( resource().path( "/management/token" )
                                          .accept( MediaType.APPLICATION_JSON )
                                          .type( MediaType.APPLICATION_JSON_TYPE )
                                          .post( String.class, payload ));
        logNode( node );
    }

    @Test
    public void testOrgPOSTParams() throws IOException {
        JsonNode node = mapper.readTree( resource().path( "/management/organizations" ).queryParam( "organization", "testOrgPOSTParams" )
                                                   .queryParam( "username", "testOrgPOSTParams" ).queryParam( "grant_type", "password" )
                                                   .queryParam( "email", "testOrgPOSTParams@apigee.com" ).queryParam( "name", "testOrgPOSTParams" )
                                                   .queryParam( "password", "password" )

                                                   .accept( MediaType.APPLICATION_JSON ).type( MediaType.APPLICATION_FORM_URLENCODED )
                                                   .post( String.class ));

        assertEquals( "ok", node.get( "status" ).asText() );
    }


    @Test
    public void testOrgPOSTForm() throws IOException {

        Form form = new Form();
        form.add( "organization", "testOrgPOSTForm" );
        form.add( "username", "testOrgPOSTForm" );
        form.add( "grant_type", "password" );
        form.add( "email", "testOrgPOSTForm@apigee.com" );
        form.add( "name", "testOrgPOSTForm" );
        form.add( "password", "password" );

        JsonNode node = mapper.readTree( resource().path( "/management/organizations" ).accept( MediaType.APPLICATION_JSON )
                                                   .type( MediaType.APPLICATION_FORM_URLENCODED ).post( String.class, form ));

        assertEquals( "ok", node.get( "status" ).asText() );
    }

    @Test
    public void noOrgDelete() throws IOException {


        String mgmtToken = adminToken();

        ClientResponse.Status status = null;
        JsonNode node = null;

        try {
            node = mapper.readTree( resource().path( "/test-organization" ).queryParam( "access_token", mgmtToken )
                                              .accept( MediaType.APPLICATION_JSON ).type( MediaType.APPLICATION_JSON_TYPE )
                                              .delete( String.class ));
        }
        catch ( UniformInterfaceException uie ) {
            status = uie.getResponse().getClientResponseStatus();
        }

        assertEquals( ClientResponse.Status.NOT_IMPLEMENTED, status );
    }

    @Test
    public void testCreateOrgUserAndReturnCorrectUsername() throws Exception {

        String mgmtToken = superAdminToken();

        Map<String, String> payload = hashMap( "username", "test-user-2" )
                .map("name", "Test User 2")
                .map("email", "test-user-2@mockserver.com")
                .map( "password", "password" );

        JsonNode node = mapper.readTree( resource().path( "/management/organizations/test-organization/users" )
                                                   .queryParam( "access_token", mgmtToken )
                                                   .accept( MediaType.APPLICATION_JSON )
                                                   .type( MediaType.APPLICATION_JSON_TYPE )
                                                   .post( String.class, payload ));

        logNode( node );
        assertNotNull( node );

        String username = node.get( "data" ).get( "user" ).get( "username" ).asText();
        String name = node.get( "data" ).get( "user" ).get( "name" ).asText();
        String email = node.get( "data" ).get( "user" ).get( "email" ).asText();

        assertNotNull( username );
        assertNotNull( name );
        assertNotNull( email );

        assertEquals( "test-user-2", username );
        assertEquals( "Test User 2", name );
        assertEquals( "test-user-2@mockserver.com", email );
    }

    //For Testing OrganizationUpdate
    @Rule
    public TestContextSetup context = new TestContextSetup( this );


    @Test
    public void testOrganizationUpdate() throws Exception {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put( "securityLevel", 5 );

        Map payload = hashMap( "email", "test-user-1@organizationresourceit.testorganizationupdate.com" )
                .map( "username", "organizationresourceit.testorganizationupdate.test-user-1" )
                .map( "name", "organizationresourceit.testorganizationupdate" ).map( "password", "password" )
                .map( "organization", "organizationresourceit.testorganizationupdate.test-org-1" )
                .map( "company", "Apigee" );


        payload.put( OrganizationsResource.ORGANIZATION_PROPERTIES, properties );

        JsonNode node = mapper.readTree( resource().path( "/management/organizations" ).accept( MediaType.APPLICATION_JSON )
                                                   .type( MediaType.APPLICATION_JSON_TYPE ).post( String.class, payload ));
        assertNotNull( node );

        refreshIndex(context.getOrgName(), context.getAppName());

        OrganizationInfo orgInfo =
                setup.getMgmtSvc().getOrganizationByName( "organizationresourceit.testorganizationupdate.test-org-1" );
        assertEquals( 5L, orgInfo.getProperties().get( "securityLevel" ) );

        payload = new HashMap();
        properties.put( "securityLevel", 6 );
        payload.put( OrganizationsResource.ORGANIZATION_PROPERTIES, properties );

        node = mapper.readTree( resource().path( "/management/organizations/organizationresourceit.testorganizationupdate.test-org-1" )
                                          .queryParam( "access_token", superAdminToken() ).accept( MediaType.APPLICATION_JSON )
                                          .type( MediaType.APPLICATION_JSON_TYPE ).put( String.class, payload ));
        logNode( node );

        refreshIndex(context.getOrgName(), context.getAppName());

        node = mapper.readTree( resource().path( "/management/organizations/organizationresourceit.testorganizationupdate.test-org-1" )
                                          .queryParam( "access_token", superAdminToken() ).accept( MediaType.APPLICATION_JSON )
                                          .type( MediaType.APPLICATION_JSON_TYPE ).get( String.class ));
        logNode( node );
        Assert.assertEquals( 6,
                node.get( "organization" ).get( OrganizationsResource.ORGANIZATION_PROPERTIES ).get( "securityLevel" )
                    .asInt() );
    }

}