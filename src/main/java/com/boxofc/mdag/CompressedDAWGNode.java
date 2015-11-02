/**
 * MDAG is a Java library capable of constructing character-sequence-storing,
 * directed acyclic graphs of minimal size.
 *
 *  Copyright (C) 2012 Kevin Lawson <Klawson88@gmail.com>
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.boxofc.mdag;

import com.boxofc.mdag.util.SemiNavigableMap;
import com.boxofc.mdag.util.LookaheadIterator;
import com.boxofc.mdag.util.SimpleEntry;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * The class capable of representing a MDAG node, its transition set, and one of its incoming transitions;
 * objects of this class are used to represent a MDAG after its been simplified in order to save space.
 
 * @author Kevin
 */
class CompressedDAWGNode extends DAWGNode {
    public static final int ACCEPT_NODE_MASK = Integer.MIN_VALUE;
    public static final int TRANSITION_SET_BEGIN_INDEX_MASK = Integer.MAX_VALUE;
    
    //The int denoting the size of this node's outgoing transition set
    private int transitionSetSize = -1;
    
    private final int index;
    
    private final CompressedDAWGSet graph;
    
    /**
     * Constructs a SimpleMDAGNode.
     
     * @param letter                a char representing the transition label leading to this SimpleMDAGNode
     * @param isAcceptNode          a boolean representing the accept state status of this SimpleMDAGNode
     * @param transitionSetSize     an int denoting the size of this transition set
     */
    public CompressedDAWGNode(CompressedDAWGSet graph, int index) {
        this.graph = graph;
        this.index = index;
    }

    /**
     * Retrieves the accept state status of this node.
     
     * @return      true if this node is an accept state, false otherwise
     */
    @Override
    public boolean isAcceptNode() {
        return index < 0 ? true : (graph.data[index] & ACCEPT_NODE_MASK) == ACCEPT_NODE_MASK;
    }
    
    /**
     * Retrieves the index in this node's containing array that its transition set begins at.
     
     * @return      an int of the index in this node's containing array at which its transition set begins
     */
    public int getTransitionSetBeginIndex() {
        return index < 0 ? 0 : graph.data[index] & TRANSITION_SET_BEGIN_INDEX_MASK;
    }
    
    /**
     * Retrieves the size of this node's outgoing transition set.
     
     * @return      an int denoting the size of this node's outgoing transition set
     */
    public int getOutgoingTransitionsSize() {
        if (transitionSetSize < 0) {
            if (index < 0)
                transitionSetSize = 0;
            else {
                int from = index + 1;
                int to = index + graph.getTransitionSizeInInts();
                int s = 0;
                for (int i = from; i < to; i++)
                    s += Integer.bitCount(graph.data[i]);
                transitionSetSize = s;
            }
        }
        return transitionSetSize;
    }
    
    public Iterable<CompressedDAWGNode> getOutgoingTransitionsNodes() {
        return new Iterable<CompressedDAWGNode>() {
            private final int transitionSizeInInts = graph.getTransitionSizeInInts();
            private final int size = getOutgoingTransitionsSize();
            
            @Override
            public Iterator<CompressedDAWGNode> iterator() {
                return new LookaheadIterator<CompressedDAWGNode>() {
                    private int current;
                    private int childrenIdx = getTransitionSetBeginIndex();
                    
                    @Override
                    public CompressedDAWGNode nextElement() throws NoSuchElementException {
                        if (current < size) {
                            CompressedDAWGNode child = new CompressedDAWGNode(graph, childrenIdx);
                            current++;
                            childrenIdx += transitionSizeInInts;
                            return child;
                        } else
                            throw new NoSuchElementException();
                    }
                };
            }
        };
    }
    
    public SemiNavigableMap<Character, CompressedDAWGNode> getOutgoingTransitions() {
        return new SemiNavigableMap<Character, CompressedDAWGNode>() {
            private final int transitionSizeInInts = graph.getTransitionSizeInInts();
            private final int start = index + 1;
            private final int end = index + transitionSizeInInts;
            
            @Override
            public Iterator<SimpleEntry<Character, CompressedDAWGNode>> iterator() {
                return new LookaheadIterator<SimpleEntry<Character, CompressedDAWGNode>>() {
                    private int currentIdx = start;
                    private int currentValue = end == start ? 0 : graph.data[currentIdx];
                    private int childrenIdx = getTransitionSetBeginIndex();
                    
                    @Override
                    public SimpleEntry<Character, CompressedDAWGNode> nextElement() throws NoSuchElementException {
                        while (true) {
                            if (currentValue == 0) {
                                currentIdx++;
                                if (currentIdx >= end)
                                    throw new NoSuchElementException();
                                childrenIdx += transitionSizeInInts;
                                currentValue = graph.data[currentIdx];
                            } else {
                                int lowest = Integer.lowestOneBit(currentValue);
                                currentValue ^= lowest;
                                int bitShift = Integer.numberOfLeadingZeros(lowest);
                                char childLetter = graph.letters[((currentIdx - start) << 5) + bitShift];
                                CompressedDAWGNode child = new CompressedDAWGNode(graph, graph.data[childrenIdx + bitShift]);
                                return new SimpleEntry<>(childLetter, child);
                            }
                        }
                    }
                };
            }

            @Override
            public boolean isEmpty() {
                for (int i = start; i < end; i++)
                    if (graph.data[i] != 0)
                        return false;
                return true;
            }

            @Override
            public SemiNavigableMap<Character, CompressedDAWGNode> descendingMap() {
                return null;
            }
        };
    }

    public int getIndex() {
        return index;
    }
    
    @Override
    public int getId() {
        return index / graph.getTransitionSizeInInts();
    }

    @Override
    public int hashCode() {
        return index;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (!(obj instanceof CompressedDAWGNode))
            return false;
        CompressedDAWGNode other = (CompressedDAWGNode)obj;
        return index == other.index && graph == other.graph;
    }
    
    /**
     * Follows an outgoing transition from this node.
     
     * @param letter            the char representation of the desired transition's label
     * @return                  the CompressedDAWGNode that is the target of the transition labeled with {@code letter},
     *                          or null if there is no such labeled transition from this node
     */
    @Override
    public CompressedDAWGNode transition(char letter) {
        Integer letterPos = graph.getLettersIndex().get(letter);
        if (letterPos == null)
            return null;
        int lp = letterPos;
        int transitionsStart = index + 1;
        int intIndexOfLetterInArray = lp >>> 5;
        int transitionsEnd = transitionsStart + intIndexOfLetterInArray;
        lp &= 31;
        int bitIndexOfLetterInInt = 1 << lp;
        if ((graph.data[transitionsEnd] & bitIndexOfLetterInInt) == 0)
            return null;
        int pos = 0;
        for (int i = transitionsStart; i < transitionsEnd; i++)
            pos += Integer.bitCount(graph.data[i]);
        if (lp > 0)
            pos += Integer.bitCount(graph.data[transitionsEnd] << (32 - lp));
        int transitionSizeInInts = graph.getTransitionSizeInInts();
        pos *= transitionSizeInInts;
        pos += getTransitionSetBeginIndex();
        return new CompressedDAWGNode(graph, pos);
    }

    /**
     * Follows a transition path starting from this node.
     
     * @param str               a String corresponding a transition path in the MDAG
     * @return                  the CompressedDAWGNode at the end of the transition path corresponding to
                          {@code str}, or null if such a transition path is not present in the MDAG
     */
    @Override
    public CompressedDAWGNode transition(String str) {
        return (CompressedDAWGNode)super.transition(str);
    }
}