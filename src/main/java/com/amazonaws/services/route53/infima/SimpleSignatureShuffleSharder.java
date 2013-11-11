/**
 * Copyright 2013-2013 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License"). 
 * You may not use this file except in compliance with the License. 
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed 
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express 
 * or implied. See the License for the specific language governing permissions 
 * and limitations under the License. 
 */
package com.amazonaws.services.route53.infima;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.List;

/**
 * A shuffle sharder based on probabilistic hashing.
 * 
 * In traditional sharding, an identifier is sharded to one item out of many. If
 * that item is something that may fail or become contended in some way (e.g. a
 * server, a queue, a rate-limit), then traditional sharding reduces the
 * "blast radius" of any per-identifier problem or burst to a factor of 1/N of
 * the overall number of items.
 * 
 * With Shuffle Sharding we assign each identifier multiple endpoints. If the
 * dependent calling client is tolerant of partial availability, or uses Route
 * 53 healthchecks with a Rubber Tree for endpoint discovery (which is itself
 * tolerant of partial availability), the "blast radius" of any problem is
 * reduced to a factor of 1/(N choose K) where K is the number of items in the
 * shuffle shard.
 * 
 * This shuffle shard implementation uses simople probabilistic hashing to
 * compute shuffle shards.
 * 
 * @param <T>
 *            The type for items in the shuffle shards
 */
public class SimpleSignatureShuffleSharder<T> {

    private final long seed;

    /**
     * Create a SimpleSignatureShuffleSharder.
     * 
     * Shuffle sharding is like regular sharding except that instead of giving
     * each identifier one endpoint out of N, we give them M endpoints out end.
     * This drastically increases the number of shards to N choose M. If the
     * protocol or service using the sharding is tolerant of some failures or
     * contention, this also effectively reduces the blast-radius of
     * per-identifier issues to 1/(N choose M).
     * 
     * Shuffle Shards generated by this module are probablistic and derived from
     * a hash of identifiers. It's therefore important to use a seed likely to
     * be unique to your application, to protect against targeted collision
     * attacks.
     * 
     * @param seed
     *            the seed to use for this ShuffleSharder
     */
    public SimpleSignatureShuffleSharder(long seed) {
        this.seed = seed;
    }

    /**
     * Compute a shuffle shard based on an identifier
     * 
     * @param lattice
     *            The Infima Lattice to use
     * @param identifier
     *            The identifier
     * @param endpointsPerCell
     *            The number of endpoints to choose from each eligible lattice
     *            cell
     * @return An Infima Lattice representing the chosen endpoints in the
     *         shuffle shard
     * @throws NoSuchAlgorithmException
     *             This JVM does not support MD5
     */
    public Lattice<T> shuffleShard(Lattice<T> lattice, byte[] identifier, int endpointsPerCell)
            throws NoSuchAlgorithmException {
        Lattice<T> chosen = new Lattice<T>(lattice.getDimensionNames());

        /*
         * Use our per-caller identifier to compute a hash, which will service
         * as a signature
         */
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update((seed + "").getBytes());
        digest.update(identifier);
        byte[] checksum = digest.digest();

        /* Use the first 64 bits of the checksum as our seed for Random() */
        long shardSeed = 0;
        for (int i = 0; i < 8; i++) {
            shardSeed += ((long) checksum[i] & 0xFFL) << (8 * i);
        }

        /* And use that hash to seed our entropy pool */
        Random entropy = new Random(shardSeed);

        /* Shuffle the order of the values in each dimension */
        List<List<String>> shuffledDimensionValues = new ArrayList<List<String>>();
        for (String dimensionName : lattice.getDimensionNames()) {
            List<String> shuffledValues = new ArrayList<String>(lattice.getDimensionValues(dimensionName));
            Collections.shuffle(shuffledValues, entropy);
            shuffledDimensionValues.add(shuffledValues);
        }

        /* Get the dimensionality of the lattice */
        Map<String, Integer> dimensionality = lattice.getDimensionality();

        /*
         * One dimensional lattices are a special case. For a one dimensional
         * lattice, we select end-points from each cell, since there is no other
         * dimension to consider.
         */
        if (lattice.getDimensionality().size() == 1) {
            for (String dimensionValue : shuffledDimensionValues.get(0)) {
                List<T> availableEndpoints = new ArrayList<T>(lattice.getEndpointsForSector(Arrays
                        .asList(dimensionValue)));
                Collections.shuffle(availableEndpoints, entropy);
                chosen.addEndpointsForSector(Arrays.asList(dimensionValue),
                        availableEndpoints.subList(0, endpointsPerCell));
            }

            return chosen;
        }

        /* This is a multi-dimensional lattice */

        /* Which dimension has the smallest number of values in it? */
        int minimumDimensionSize = Integer.MAX_VALUE;
        for (Entry<String, Integer> entry : dimensionality.entrySet()) {
            if (entry.getValue() < minimumDimensionSize) {
                minimumDimensionSize = entry.getValue();
            }
        }

        /*
         * Build a coordinate to the chosen cells by picking the current top
         * item on each list of dimension values.
         */
        for (int i = 0; i < minimumDimensionSize; i++) {
            List<String> coordinates = new ArrayList<String>();

            for (int j = 0; j < lattice.getDimensionNames().size(); j++) {
                coordinates.add(shuffledDimensionValues.get(j).remove(0));
            }

            List<T> availableEndpoints = new ArrayList<T>(lattice.getEndpointsForSector(coordinates));
            Collections.shuffle(availableEndpoints, entropy);
            chosen.addEndpointsForSector(coordinates, availableEndpoints.subList(0, endpointsPerCell));
        }

        return chosen;
    }
}
