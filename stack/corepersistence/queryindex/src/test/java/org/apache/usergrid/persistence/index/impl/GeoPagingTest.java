/*
 *
 *  *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *  *
 *
 */
package org.apache.usergrid.persistence.index.impl;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.usergrid.persistence.core.guice.MigrationManagerRule;
import org.apache.usergrid.persistence.core.scope.ApplicationScopeImpl;
import org.apache.usergrid.persistence.core.test.UseModules;
import org.apache.usergrid.persistence.index.ApplicationEntityIndex;
import org.apache.usergrid.persistence.index.CandidateResult;
import org.apache.usergrid.persistence.index.CandidateResults;
import org.apache.usergrid.persistence.index.EntityIndex;
import org.apache.usergrid.persistence.index.EntityIndexBatch;
import org.apache.usergrid.persistence.index.EntityIndexFactory;
import org.apache.usergrid.persistence.index.IndexEdge;
import org.apache.usergrid.persistence.index.SearchEdge;
import org.apache.usergrid.persistence.index.SearchTypes;
import org.apache.usergrid.persistence.index.guice.TestIndexModule;
import org.apache.usergrid.persistence.index.utils.UUIDUtils;
import org.apache.usergrid.persistence.model.entity.Entity;
import org.apache.usergrid.persistence.model.field.IntegerField;
import org.apache.usergrid.persistence.model.field.LocationField;
import org.apache.usergrid.persistence.model.field.StringField;
import org.apache.usergrid.persistence.model.field.value.Location;
import org.apache.usergrid.persistence.model.util.EntityUtils;
import org.apache.usergrid.persistence.model.util.UUIDGenerator;

import com.google.inject.Inject;

import static org.apache.usergrid.persistence.core.util.IdGenerator.createId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;


/**
 * // TODO: Document this
 *
 * @author ApigeeCorporation
 * @since 4.0
 */

@RunWith( EsRunner.class )
@UseModules( { TestIndexModule.class } )
public class GeoPagingTest extends BaseIT {
    private static Logger log = LoggerFactory.getLogger( GeoPagingTest.class );

    @Inject
    public EntityIndexFactory eif;

    @Inject
    public EntityIndex ei;

    @Inject
    @Rule
    public MigrationManagerRule migrationManagerRule;


    @Before
    public void setup() {
        ei.initialize();
    }



    /**
     * Test that geo-query returns co-located entities in expected order.
     */
    @Test
    public void groupQueriesWithDistanceOrderedResults() throws Exception {

        int maxRangeLimit = 9;
        Entity[] cats = new Entity[maxRangeLimit + 1];

        final ApplicationEntityIndex
                applicationEntityIndex = eif.createApplicationEntityIndex( new ApplicationScopeImpl( createId( "application" ) ) );

        final EntityIndexBatch batch = applicationEntityIndex.createBatch();


        final IndexEdge edge = new IndexEdgeImpl(createId("root"), "testType", SearchEdge.NodeType.SOURCE,  1000  );

        //Create several entities, saved in reverse order so we have highest time UUID as "closest" to ensure that
        //our geo order works correctly
        for ( int i = maxRangeLimit; i >= 0; i-- ) {
            Entity cat = new Entity("cat");
            EntityUtils.setVersion( cat, UUIDGenerator.newTimeUUID() );


            cat.setField( new StringField("name", "cat" + i ));
            cat.setField( new LocationField("location", new Location(37.0 + i ,  -75.0 + i ) ) );
            cats[i] = cat;

            batch.index( edge, cat );

        }

        batch.execute().get();

        ei.refresh();



        final String query =  "select * where location within 1500000 of 37, -75" ;

        final CandidateResults
                candidates = applicationEntityIndex.search( edge, SearchTypes.fromTypes( "cat" ), query, 100 );

        assertNotNull( candidates );


        for ( int consistent = 0; consistent < maxRangeLimit; consistent++ ) {
            //got entities back, just need to page through them and make sure that i got them in location order.
            CandidateResult candidate = candidates.get( consistent );
            assertNotNull( candidate );


            final Entity expected = cats[consistent];
            assertEquals(expected.getId(), candidate.getId());


        }
    }
}