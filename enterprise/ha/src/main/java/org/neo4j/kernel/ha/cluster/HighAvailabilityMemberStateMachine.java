/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.ha.cluster;

import java.net.URI;

import org.neo4j.cluster.InstanceId;
import org.neo4j.cluster.member.ClusterMemberEvents;
import org.neo4j.cluster.member.ClusterMemberListener;
import org.neo4j.cluster.protocol.election.Election;
import org.neo4j.helpers.Listeners;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.AvailabilityGuard;
import org.neo4j.kernel.ha.cluster.member.ObservedClusterMembers;
import org.neo4j.kernel.impl.store.StoreId;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;

import static org.neo4j.cluster.util.Quorums.isQuorum;

/**
 * State machine that listens for global cluster events, and coordinates
 * the internal transitions between ClusterMemberStates. Internal services
 * that wants to know what is going on should register ClusterMemberListener implementations
 * which will receive callbacks on state changes.
 */
public class HighAvailabilityMemberStateMachine extends LifecycleAdapter implements HighAvailability,
        AvailabilityGuard.AvailabilityRequirement
{
    private final HighAvailabilityMemberContext context;
    private final AvailabilityGuard availabilityGuard;
    private final ClusterMemberEvents events;
    private final StringLogger logger;
    private final ObservedClusterMembers observedMembers;
    private final Election election;

    private Iterable<HighAvailabilityMemberListener> memberListeners = Listeners.newListeners();
    private StateMachineClusterEventListener eventsListener;
    private volatile HighAvailabilityMemberState state;

    public HighAvailabilityMemberStateMachine( HighAvailabilityMemberContext context,
                                               AvailabilityGuard availabilityGuard,
                                               ObservedClusterMembers observedMembers,
                                               ClusterMemberEvents events,
                                               Election election,
                                               StringLogger logger )
    {
        this.context = context;
        this.availabilityGuard = availabilityGuard;
        this.observedMembers = observedMembers;
        this.events = events;
        this.election = election;
        this.logger = logger;
        state = HighAvailabilityMemberState.PENDING;
    }

    @Override
    public void init() throws Throwable
    {
        events.addClusterMemberListener( eventsListener = new StateMachineClusterEventListener() );
        // On initial startup, disallow database access
        availabilityGuard.deny( this );
    }

    @Override
    public void stop() throws Throwable
    {
        events.removeClusterMemberListener( eventsListener );
        HighAvailabilityMemberState oldState = state;
        state = HighAvailabilityMemberState.PENDING;
        final HighAvailabilityMemberChangeEvent event =
                new HighAvailabilityMemberChangeEvent( oldState, state, null, null );
        Listeners.notifyListeners( memberListeners, new Listeners.Notification<HighAvailabilityMemberListener>()
        {
            @Override
            public void notify( HighAvailabilityMemberListener listener )
            {
                listener.instanceStops( event );
            }
        } );

        // If we are in a state that allows access, we must deny now that we shut down.
        if ( oldState.isAccessAllowed() )
        {
            availabilityGuard.deny( this );
        }

        context.setAvailableHaMasterId( null );
    }

    @Override
    public void addHighAvailabilityMemberListener( HighAvailabilityMemberListener toAdd )
    {
        memberListeners = Listeners.addListener( toAdd, memberListeners );
    }

    @Override
    public void removeHighAvailabilityMemberListener( HighAvailabilityMemberListener toRemove )
    {
        memberListeners = Listeners.removeListener( toRemove, memberListeners );
    }

    public HighAvailabilityMemberState getCurrentState()
    {
        return state;
    }

    @Override
    public String description()
    {
        return "Cluster state is '" + getCurrentState() + "'";
    }

    private class StateMachineClusterEventListener implements ClusterMemberListener
    {
        @Override
        public synchronized void coordinatorIsElected( InstanceId coordinatorId )
        {
            try
            {
                HighAvailabilityMemberState oldState = state;
                InstanceId previousElected = context.getElectedMasterId();

                // Check if same coordinator was elected
//                if ( !coordinatorId.equals( previousElected ) )
                {
                    context.setAvailableHaMasterId( null );
                    state = state.masterIsElected( context, coordinatorId );


                    context.setElectedMasterId( coordinatorId );
                    final HighAvailabilityMemberChangeEvent event =
                            new HighAvailabilityMemberChangeEvent( oldState, state, coordinatorId, null );
                    Listeners.notifyListeners( memberListeners,
                            new Listeners.Notification<HighAvailabilityMemberListener>()
                            {
                                @Override
                                public void notify( HighAvailabilityMemberListener listener )
                                {
                                    listener.masterIsElected( event );
                                }
                            } );

                    if ( oldState.isAccessAllowed() && oldState != state )
                    {
                        availabilityGuard.deny(HighAvailabilityMemberStateMachine.this);
                    }

                    logger.debug( "Got masterIsElected(" + coordinatorId + "), moved to " + state + " from " + oldState
                            + ". Previous elected master is " + previousElected );
                }
            }
            catch ( Throwable t )
            {
                throw new RuntimeException( t );
            }
        }

        @Override
        public synchronized void memberIsAvailable( String role, InstanceId instanceId, URI roleUri, StoreId storeId )
        {
            try
            {
                if ( role.equals( HighAvailabilityModeSwitcher.MASTER ) )
                {
//                    if ( !roleUri.equals( context.getAvailableHaMaster() ) )
                    {
                        HighAvailabilityMemberState oldState = state;
                        context.setAvailableHaMasterId( roleUri );
                        state = state.masterIsAvailable( context, instanceId, roleUri );
                        logger.debug( "Got masterIsAvailable(" + instanceId + "), moved to " + state + " from " +
                                oldState );
                        final HighAvailabilityMemberChangeEvent event = new HighAvailabilityMemberChangeEvent( oldState,
                                state, instanceId, roleUri );
                        Listeners.notifyListeners( memberListeners,
                                new Listeners.Notification<HighAvailabilityMemberListener>()
                                {
                                    @Override
                                    public void notify( HighAvailabilityMemberListener listener )
                                    {
                                        listener.masterIsAvailable( event );
                                    }
                                } );

                        if ( oldState == HighAvailabilityMemberState.TO_MASTER && state ==
                                HighAvailabilityMemberState.MASTER )
                        {
                            availabilityGuard.grant( HighAvailabilityMemberStateMachine.this );
                        }
                    }
                }
                else if ( role.equals( HighAvailabilityModeSwitcher.SLAVE ) )
                {
                    HighAvailabilityMemberState oldState = state;
                    state = state.slaveIsAvailable( context, instanceId, roleUri );
                    logger.debug( "Got slaveIsAvailable(" + instanceId + "), " +
                            "moved to " + state + " from " + oldState );
                    final HighAvailabilityMemberChangeEvent event = new HighAvailabilityMemberChangeEvent( oldState,
                            state, instanceId, roleUri );
                    Listeners.notifyListeners( memberListeners,
                            new Listeners.Notification<HighAvailabilityMemberListener>()
                            {
                                @Override
                                public void notify( HighAvailabilityMemberListener listener )
                                {
                                    listener.slaveIsAvailable( event );
                                }
                            } );

                    if ( oldState == HighAvailabilityMemberState.TO_SLAVE &&
                            state == HighAvailabilityMemberState.SLAVE )
                    {
                        availabilityGuard.grant( HighAvailabilityMemberStateMachine.this );
                    }
                }
            }
            catch ( Throwable throwable )
            {
                logger.warn( "Exception while receiving member availability notification", throwable );
            }
        }


        /**
         * As soon as we receive an unavailability message and the instanceId belongs to us, depending on the current
         * state we do the following:
         * <ul>
         * <li>if current state is <b>not</b> {@link HighAvailabilityMemberState#PENDING} we trigger switch to
         * {@link
         * HighAvailabilityMemberState#PENDING} and force new elections.</li>
         * <li>if current state is {@link HighAvailabilityMemberState#PENDING}
         * we only log debug message</li>
         * </ul>
         * The assumption here is: as soon as we receive unavailability event about us - then something went wrong
         * in a cluster and we need to perform new elections.
         * Elections should be triggered for all states except {@link HighAvailabilityMemberState#PENDING}, since
         * first of all there is nothing or we already made a switch and waiting election to start, so no reason to
         * start them again.
         * <p>
         * Listener invoked from sync block in {@link org.neo4j.cluster.member.paxos.PaxosClusterMemberEvents} so we
         * should not have any racing here.
         *</p>
         * @param role The role for which the member is unavailable
         * @param unavailableId The id of the member which became unavailable for that role
         */
        @Override
        public void memberIsUnavailable( String role, InstanceId unavailableId )
        {
            if ( context.getMyId().equals( unavailableId ) )
            {
                if ( HighAvailabilityMemberState.PENDING != state )
                {
                    HighAvailabilityMemberState oldState = state;
                    changeStateToPending();
                    logger.debug( "Got memberIsUnavailable(" + unavailableId + "), moved to " + state + " from " +
                                  oldState );
                    logger.debug( "Forcing new round of elections." );
                    election.performRoleElections();
                }
                else
                {
                    logger.debug( "Got memberIsUnavailable(" + unavailableId + "), but already in " +
                                  HighAvailabilityMemberState.PENDING + " state, will skip state change and " +
                                  "new election.");
                }
            }
            else
            {
                logger.debug( "Got memberIsUnavailable(" + unavailableId + ")" );
            }
        }

        @Override
        public void memberIsFailed( InstanceId instanceId )
        {
            if ( !isQuorum( getAliveCount(), getTotalCount() ) )
            {
                HighAvailabilityMemberState oldState = state;
                changeStateToPending();
                logger.debug( "Got memberIsFailed(" + instanceId + ") and cluster lost quorum to continue, moved to "
                        + state + " from " + oldState );
            }
            else
            {
                logger.debug( "Got memberIsFailed(" + instanceId + ")" );
            }
        }

        @Override
        public void memberIsAlive( InstanceId instanceId )
        {
            if ( isQuorum(getAliveCount(), getTotalCount()) && state.equals( HighAvailabilityMemberState.PENDING ) )
            {
                election.performRoleElections();
            }
        }

        private void changeStateToPending()
        {
            if ( state.isAccessAllowed() )
            {
                availabilityGuard.deny( HighAvailabilityMemberStateMachine.this );
            }

            final HighAvailabilityMemberChangeEvent event =
                    new HighAvailabilityMemberChangeEvent( state, HighAvailabilityMemberState.PENDING, null, null );

            state = HighAvailabilityMemberState.PENDING;

            Listeners.notifyListeners( memberListeners, new Listeners.Notification<HighAvailabilityMemberListener>()
            {
                @Override
                public void notify( HighAvailabilityMemberListener listener )
                {
                    listener.instanceStops( event );
                }
            } );

            context.setAvailableHaMasterId( null );
            context.setElectedMasterId( null );
        }

        private long getAliveCount()
        {
            return Iterables.count( observedMembers.getAliveMembers() );
        }

        private long getTotalCount()
        {
            return Iterables.count( observedMembers.getMembers() );
        }
    }
}
