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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.NavigableSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A minimalistic directed acyclical graph suitable for storing a set of Strings.
 
 * @author Kevin
 */
public class MDAG {
    //Increment for node identifiers.
    private int id;
    
    //MDAGNode from which all others in the structure are reachable (all manipulation and non-simplified MDAG search operations begin from this).
    private MDAGNode sourceNode = new MDAGNode(false, id++);

    //SimpleMDAGNode from which all others in the structure are reachable (will be defined if this MDAG is simplified)
    private SimpleMDAGNode simplifiedSourceNode;

    //HashMap which contains the MDAGNodes collectively representing the all unique equivalence classes in the MDAG.
    //Uniqueness is defined by the types of transitions allowed from, and number and type of nodes reachable
    //from the node of interest. Since there are no duplicate nodes in an MDAG, # of equivalence classes == # of nodes.
    private HashMap<MDAGNode, MDAGNode> equivalenceClassMDAGNodeHashMap = new HashMap<>();
    
    //Array that will contain a space-saving version of the MDAG after a call to simplify().
    private SimpleMDAGNode[] mdagDataArray;
    
    //NavigableSet which will contain the set of unique characters used as transition labels in the MDAG
    private final TreeSet<Character> charTreeSet = new TreeSet<>();
    
    //An int denoting the total number of transitions between the nodes of the MDAG
    private int transitionCount;
    
    //Total number of words contained in this MDAG.
    private int size;
    
    //Enum containing fields collectively denoting the set of all conditions that can be applied to a search on the MDAG
    private static enum SearchCondition {
        NO_SEARCH_CONDITION, PREFIX_SEARCH_CONDITION, SUBSTRING_SEARCH_CONDITION, SUFFIX_SEARCH_CONDITION;
        
        /**
        * Determines whether two Strings have a given type of relationship.

        * @param processingString      a String
        * @param conditionString       a String
        * @param searchCondition       an int denoting the type of condition to be satisfied
        * @return                      true if {@code processingString} has a relationship with
        *                              {@code conditionString} described by the condition
        *                              represented by {@code searchCondition}
        */
        public boolean satisfiesCondition(String str1, String str2) {
            boolean satisfiesSearchCondition;
         
            switch (this) {
                case PREFIX_SEARCH_CONDITION:
                    satisfiesSearchCondition = str1.startsWith(str2);
                    break;
                case SUBSTRING_SEARCH_CONDITION:
                    satisfiesSearchCondition = str1.contains(str2);
                    break;
                case SUFFIX_SEARCH_CONDITION:
                    satisfiesSearchCondition = str1.endsWith(str2);
                    break;
                default:
                    satisfiesSearchCondition = true;
                    break;
            }

            return satisfiesSearchCondition;
        }
    };
    
    /**
     * Creates an MDAG from a collection of Strings.
     
     * @param strCollection     a {@link java.util.Iterable} containing Strings that the MDAG will contain
     */
    public MDAG(Iterable<? extends String> strCollection) {
        addAll(strCollection);
    }
    
    /**
     * Creates an MDAG from a collection of Strings.
     
     * @param strCollection     a {@link java.util.Iterable} containing Strings that the MDAG will contain
     */
    public MDAG(String... strCollection) {
        addAll(strCollection);
    }
    
    /**
     * Creates empty MDAG. Use {@link #addString} to fill it.
     */
    public MDAG() {
    }
    
    /**
     * Creates a MDAG from a newline delimited file containing the data of interest.
     
     * @param dataFile          a {@link java.io.InputStream} representation of a file
     *                          containing the Strings that the MDAG will contain
     * @return true if and only if this MDAG was changed as a result of this call
     * @throws IOException      if {@code datafile} cannot be opened, or a read operation on it cannot be carried out
     */
    public boolean addAll(InputStream dataFile) throws IOException {
        final IOException exceptionToThrow[] = new IOException[1];
        try (InputStreamReader isr = new InputStreamReader(dataFile);
             final BufferedReader br = new BufferedReader(isr)) {
            return addAll(new Iterable<String>() {
                @Override
                public Iterator<String> iterator() {
                    return new Iterator<String>() {
                        String nextLine;

                        @Override
                        public boolean hasNext() {
                            if (nextLine == null) {
                                try {
                                    nextLine = br.readLine();
                                    return nextLine != null;
                                } catch (IOException e) {
                                    exceptionToThrow[0] = e;
                                    throw new RuntimeException(e);
                                }
                            } else
                                return true;
                        }

                        @Override
                        public String next() {
                            if (nextLine != null || hasNext()) {
                                String line = nextLine;
                                nextLine = null;
                                return line;
                            } else
                                throw new NoSuchElementException();
                        }
                    };
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() == exceptionToThrow[0] && exceptionToThrow[0] != null)
                throw exceptionToThrow[0];
            throw e;
        }
    }
    
    /**
     * Adds a Collection of Strings to the MDAG.
     
     * @param strCollection     a {@link java.util.Collection} containing Strings to be added to the MDAG
     * @return true if and only if this MDAG was changed as a result of this call
     */
    public final boolean addAll(String... strCollection) {
        return addAll(Arrays.asList(strCollection));
    }
    
    /**
     * Adds a Collection of Strings to the MDAG.
     
     * @param strCollection     a {@link java.util.Iterable} containing Strings to be added to the MDAG
     * @return true if and only if this MDAG was changed as a result of this call
     */
    public final boolean addAll(Iterable<? extends String> strCollection) {
        if (sourceNode != null) {
            boolean result = false;
            String previousString = "";
        
            //Add all the Strings in strCollection to the MDAG.
            for (String currentString : strCollection) {
                int mpsIndex = calculateMinimizationProcessingStartIndex(previousString, currentString);

                //If the transition path of the previousString needs to be examined for minimization or
                //equivalence class representation after a certain point, call replaceOrRegister to do so.
                if (mpsIndex != -1) {
                    String transitionSubstring = previousString.substring(0, mpsIndex);
                    String minimizationProcessingSubString = previousString.substring(mpsIndex);
                    replaceOrRegister(sourceNode.transition(transitionSubstring), minimizationProcessingSubString);
                }

                result |= addStringInternal(currentString);
                previousString = currentString;
            }

            //Since we delay the minimization of the previously-added String
            //until after we read the next one, we need to have a seperate
            //statement to minimize the absolute last String.
            replaceOrRegister(sourceNode, previousString);
            return result;
        } else
            throw new UnsupportedOperationException("MDAG is simplified. Unable to add additional Strings.");
    }
    
    /**
     * Adds a string to the MDAG.
     
     * @param str       the String to be added to the MDAG
     * @return true if MDAG didn't contain this string yet
     */
    public boolean add(String str) {
        if (sourceNode != null) {
            boolean result = addStringInternal(str);
            replaceOrRegister(sourceNode, str);
            return result;
        } else
            throw new UnsupportedOperationException("MDAG is simplified. Unable to add additional Strings.");
    }
    
    private void splitTransitionPath(MDAGNode originNode, String storedStringSubstr) {
        HashMap<String, Object> firstConfluenceNodeDataHashMap = getTransitionPathFirstConfluenceNodeData(originNode, storedStringSubstr);
        Integer toFirstConfluenceNodeTransitionCharIndex = (Integer)firstConfluenceNodeDataHashMap.get("toConfluenceNodeTransitionCharIndex");
        MDAGNode firstConfluenceNode = (MDAGNode)firstConfluenceNodeDataHashMap.get("confluenceNode");
        
        if (firstConfluenceNode != null) {
            MDAGNode firstConfluenceNodeParent = originNode.transition(storedStringSubstr.substring(0, toFirstConfluenceNodeTransitionCharIndex));
            MDAGNode firstConfluenceNodeClone = firstConfluenceNode.clone(firstConfluenceNodeParent, storedStringSubstr.charAt(toFirstConfluenceNodeTransitionCharIndex), id++);
            transitionCount += firstConfluenceNodeClone.getOutgoingTransitionCount();
            String unprocessedSubString = storedStringSubstr.substring(toFirstConfluenceNodeTransitionCharIndex + 1);
            splitTransitionPath(firstConfluenceNodeClone, unprocessedSubString);
        }
    }
    
    /**
     * Calculates the length of the the sub-path in a transition path, that is used only by a given string.
     
     * @param str       a String corresponding to a transition path from sourceNode
     * @return          an int denoting the size of the sub-path in the transition path
     *                  corresponding to {@code str} that is only used by {@code str}
     */
    private int calculateSoleTransitionPathLength(String str) {
        Stack<MDAGNode> transitionPathNodeStack = sourceNode.getTransitionPathNodes(str);
        transitionPathNodeStack.pop();  //The MDAGNode at the top of the stack is not needed
                                        //(we are processing the outgoing transitions of nodes inside str's transition path,
                                        //the outgoing transitions of the MDAGNode at the top of the stack are outside this path)
        
        transitionPathNodeStack.trimToSize();

        //Process each node in transitionPathNodeStack, using each to determine whether the
        //transition path corresponding to str is only used by str.  This is true if and only if
        //each node in the transition path has a single outgoing transition and is not an accept state.
        while (!transitionPathNodeStack.isEmpty()) {
            MDAGNode currentNode = transitionPathNodeStack.peek();
            if (currentNode.getOutgoingTransitions().size() <= 1 && !currentNode.isAcceptNode())
                transitionPathNodeStack.pop();
            else
                break;
        }
        
        return transitionPathNodeStack.capacity() - transitionPathNodeStack.size();
    }
    
    /**
     * Removes a String from the MDAG.
     
     * @param str       the String to be removed from the MDAG
     * @return true if MDAG already contained this string
     */
    public boolean remove(String str) {
        if (sourceNode != null) {
            //Split the transition path corresponding to str to ensure that
            //any other transition paths sharing nodes with it are not affected
            splitTransitionPath(sourceNode, str);

            //Remove from equivalenceClassMDAGNodeHashMap, the entries of all the nodes in the transition path corresponding to str.
            removeTransitionPathRegisterEntries(str);

            //Get the last node in the transition path corresponding to str
            MDAGNode strEndNode = sourceNode.transition(str);

            if (!strEndNode.hasOutgoingTransitions()) {
                int soleInternalTransitionPathLength = calculateSoleTransitionPathLength(str);
                int internalTransitionPathLength = str.length() - 1;

                if (soleInternalTransitionPathLength == internalTransitionPathLength) {
                    sourceNode.removeOutgoingTransition(str.charAt(0));
                    transitionCount -= str.length();
                } else {
                    //Remove the sub-path in str's transition path that is only used by str
                    int toBeRemovedTransitionLabelCharIndex = (internalTransitionPathLength - soleInternalTransitionPathLength);
                    MDAGNode latestNonSoloTransitionPathNode = sourceNode.transition(str.substring(0, toBeRemovedTransitionLabelCharIndex));
                    latestNonSoloTransitionPathNode.removeOutgoingTransition(str.charAt(toBeRemovedTransitionLabelCharIndex));
                    transitionCount -= str.substring(toBeRemovedTransitionLabelCharIndex).length();
                    
                    replaceOrRegister(sourceNode, str.substring(0, toBeRemovedTransitionLabelCharIndex));
                }
                size--;
                return true;
            } else {
                boolean result = strEndNode.setAcceptStateStatus(false);
                replaceOrRegister(sourceNode, str);
                if (result)
                    size--;
                return result;
            }
        } else
            throw new UnsupportedOperationException("MDAG is simplified. Unable to remove any Strings.");
    }
    
    /**
     * Determines the start index of the substring in the String most recently added to the MDAG
     * that corresponds to the transition path that will be next up for minimization processing.
     *
     * The "minimization processing start index" is defined as the index in {@code prevStr} which starts the substring
     * corresponding to the transition path that doesn't have its right language extended by {@code currStr}. The transition path of
     * the substring before this point is not considered for minimization in order to limit the amount of times the
     * equivalence classes of its nodes will need to be reassigned during the processing of Strings which share prefixes.
     
     * @param prevStr       the String most recently added to the MDAG
     * @param currStr       the String next to be added to the MDAG
     * @return              an int of the index in {@code prevStr} that starts the substring corresponding
     *                      to the transition path next up for minimization processing
     */
    int calculateMinimizationProcessingStartIndex(String prevStr, String currStr) {
        int mpsIndex;
        
        if (!currStr.startsWith(prevStr)) {
            //Loop through the corresponding indices of both Strings in search of the first index containing differing characters.
            //The transition path of the substring of prevStr from this point will need to be submitted for minimization processing.
            //The substring before this point, however, does not, since currStr will simply be extending the right languages of the
            //nodes on its transition path.
            int shortestStringLength = Math.min(prevStr.length(), currStr.length());
            for (mpsIndex = 0; mpsIndex < shortestStringLength && prevStr.charAt(mpsIndex) == currStr.charAt(mpsIndex);)
                mpsIndex++;
        } else
            mpsIndex =  -1;    //If the prevStr is a prefix of currStr, then currStr simply extends the right language of the transition path of prevStr.
        
        return mpsIndex;
    }
    
    /**
     * Determines the longest prefix of a given String that is
     * the prefix of another String previously added to the MDAG.
     
     * @param str       the String to be processed
     * @return          a String of the longest prefix of {@code str}
     *                  that is also a prefix of a String contained in the MDAG
     */
    public String determineLongestPrefixInMDAG(String str) {
        MDAGNode currentNode = sourceNode;
        int numberOfChars = str.length();
        int onePastPrefixEndIndex = 0;
        
        //Loop through the characters in str, using them in sequence to transition
        //through the MDAG until the currently processing node doesn't have a transition
        //labeled with the current processing char, or there are no more characters to process.
        for (int i = 0; i < numberOfChars; i++, onePastPrefixEndIndex++) {
            char currentChar = str.charAt(i);
            if (currentNode.hasOutgoingTransition(currentChar))
                currentNode = currentNode.transition(currentChar);
            else
                break;
        }
        
        return str.substring(0, onePastPrefixEndIndex);
    }
    
    /**
     * Determines and retrieves data related to the first confluence node
     * (defined as a node with two or more incoming transitions) of a
     * transition path corresponding to a given String from a given node.
     
     * @param originNode        the MDAGNode from which the transition path corresponding to str starts from
     * @param str               a String corresponding to a transition path in the MDAG
     * @return                  a HashMap of Strings to Objects containing:
     *                              - an int denoting the length of the path to the first confluence node in the transition path of interest
     *                              - the MDAGNode which is the first confluence node in the transition path of interest (or null if one does not exist)
     */
    HashMap<String, Object> getTransitionPathFirstConfluenceNodeData(MDAGNode originNode, String str) {
        int currentIndex = 0;
        int charCount = str.length();
        MDAGNode currentNode = originNode;
        
        //Loop thorugh the characters in str, sequentially using them to transition through the MDAG in search of
        //(and breaking upon reaching) the first node that is the target of two or more transitions. The loop is
        //also broken from if the currently processing node doesn't have a transition labeled with the currently processing char.
        for (; currentIndex < charCount; currentIndex++) {
            char currentChar = str.charAt(currentIndex);
            currentNode = currentNode.hasOutgoingTransition(currentChar) ? currentNode.transition(currentChar) : null;
            
            if (currentNode == null || currentNode.isConfluenceNode())
                break;
        }
        
        boolean noConfluenceNode = currentNode == originNode || currentIndex == charCount;
        
        //Create a HashMap containing the index of the last char in the substring corresponding
        //to the transitoin path to the confluence node, as well as the actual confluence node
        HashMap<String, Object> confluenceNodeDataHashMap = new HashMap<>(2);
        confluenceNodeDataHashMap.put("toConfluenceNodeTransitionCharIndex", noConfluenceNode ? null : currentIndex);
        confluenceNodeDataHashMap.put("confluenceNode", noConfluenceNode ? null : currentNode);

        return confluenceNodeDataHashMap;
    }

    /**
     * Performs minimization processing on a transition path starting from a given node.
     *
     * This entails either replacing a node in the path with one that has an equivalent right language/equivalence class
     * (defined as set of transition paths that can be traversed and nodes able to be reached from it), or making it
     * a representative of a right language/equivalence class if a such a node does not already exist.
     
     * @param originNode        the MDAGNode that the transition path corresponding to str starts from
     * @param str              a String related to a transition path
     */
    private void replaceOrRegister(MDAGNode originNode, String str) {
        char transitionLabelChar = str.charAt(0);
        MDAGNode relevantTargetNode = originNode.transition(transitionLabelChar);

        //If relevantTargetNode has transitions and there is at least one char left to process, recursively call
        //this on the next char in order to further processing down the transition path corresponding to str
        if (relevantTargetNode.hasOutgoingTransitions() && !str.substring(1).isEmpty())
            replaceOrRegister(relevantTargetNode, str.substring(1));

        //Get the node representing the equivalence class that relevantTargetNode belongs to. MDAGNodes hash on the
        //transitions paths that can be traversed from them and nodes able to be reached from them;
        //nodes with the same equivalence classes will hash to the same bucket.
        MDAGNode equivalentNode = equivalenceClassMDAGNodeHashMap.get(relevantTargetNode);
        
        //if there is no node with the same right language as relevantTargetNode
        if (equivalentNode == null)
            equivalenceClassMDAGNodeHashMap.put(relevantTargetNode, relevantTargetNode);
        //if there is another node with the same right language as relevantTargetNode, reassign the
        //transition between originNode and relevantTargetNode, to originNode and the node representing the equivalence class of interest
        else if (equivalentNode != relevantTargetNode) {
            relevantTargetNode.decrementTargetIncomingTransitionCounts();
            transitionCount -= relevantTargetNode.getOutgoingTransitionCount(); //Since this method is recursive, the outgoing transitions of all of relevantTargetNode's child nodes have already been reassigned,
                                                                                //so we only need to decrement the transition count by the relevantTargetNode's outgoing transition count
            originNode.reassignOutgoingTransition(transitionLabelChar, relevantTargetNode, equivalentNode);
        }
    }
    
    /**
     * Adds a transition path starting from a specific node in the MDAG.
     
     * @param originNode    the MDAGNode which will serve as the start point of the to-be-created transition path
     * @param str           the String to be used to create a new transition path from {@code originNode}
     * @return true if and only if MDAG has changed as a result of this call
     */
    private boolean addTransitionPath(MDAGNode originNode, String str) {
        if (!str.isEmpty()) {
            MDAGNode currentNode = originNode;
            int charCount = str.length();

            //Loop through the characters in str, iteratevely adding
            // a transition path corresponding to it from originNode
            for (int i = 0; i < charCount; i++, transitionCount++) {
                char currentChar = str.charAt(i);
                boolean isLastChar = i == charCount - 1;
                currentNode = currentNode.addOutgoingTransition(currentChar, isLastChar, id++);
                
                charTreeSet.add(currentChar);
            }
            size++;
            return true;
        } else if (originNode.setAcceptStateStatus(true)) {
            size++;
            return true;
        } else
            return false;
    }
    
    /**
     * Removes from equivalenceClassMDAGNodeHashmap the entries of all the nodes in a transition path.
     
     * @param str       a String corresponding to a transition path from sourceNode
     */
    private void removeTransitionPathRegisterEntries(String str) {
        MDAGNode currentNode = sourceNode;

        int charCount = str.length();
        
        for (int i = 0; i < charCount; i++) {
            currentNode = currentNode.transition(str.charAt(i));
            if (equivalenceClassMDAGNodeHashMap.get(currentNode) == currentNode)
                equivalenceClassMDAGNodeHashMap.remove(currentNode);
            
            //The hashCode of an MDAGNode is cached the first time a hash is performed without a cache value present.
            //Since we just hashed currentNode, we must clear this regardless of its presence in equivalenceClassMDAGNodeHashMap
            //since we're not actually declaring equivalence class representatives here.
            currentNode.clearStoredHashCode();
        }
    }
    
    /**
     * Clones a transition path from a given node.
     
     * @param pivotConfluenceNode               the MDAGNode that the cloning operation is to be based from
     * @param transitionStringToPivotNode       a String which corresponds with a transition path from souceNode to {@code pivotConfluenceNode}
     * @param str                               a String which corresponds to the transition path from {@code pivotConfluenceNode} that is to be cloned
     */
    private void cloneTransitionPath(MDAGNode pivotConfluenceNode, String transitionStringToPivotNode, String str) {
        MDAGNode lastTargetNode = pivotConfluenceNode.transition(str);      //Will store the last node which was used as the base of a cloning operation
        MDAGNode lastClonedNode = null;                                     //Will store the last cloned node
        char lastTransitionLabelChar = '\0';                                //Will store the char which labels the transition to lastTargetNode from its parent node in the prefixString's transition path

        //Loop backwards through the indices of str, using each as a boundary to create substrings of str of decreasing length
        //which will be used to transition to, and duplicate the nodes in the transition path of str from pivotConfluenceNode.
        for (int i = str.length(); i >= 0; i--) {
            String currentTransitionString = i > 0 ? str.substring(0, i) : null;
            MDAGNode currentTargetNode = i > 0 ? pivotConfluenceNode.transition(currentTransitionString) : pivotConfluenceNode;
            MDAGNode clonedNode;

            //if we have reached pivotConfluenceNode
            if (i == 0) {
                //Clone pivotConfluenceNode in a way that reassigns the transition of its parent node (in transitionStringToConfluenceNode's path) to the clone.
                String transitionStringToPivotNodeParent = transitionStringToPivotNode.substring(0, transitionStringToPivotNode.length() - 1);
                char parentTransitionLabelChar = transitionStringToPivotNode.charAt(transitionStringToPivotNode.length() - 1);
                clonedNode = pivotConfluenceNode.clone(sourceNode.transition(transitionStringToPivotNodeParent), parentTransitionLabelChar, id++);
            } else
                clonedNode = new MDAGNode(currentTargetNode, id++);     //simply clone curentTargetNode

            transitionCount += clonedNode.getOutgoingTransitionCount();

            //If this isn't the first node we've cloned, reassign clonedNode's transition labeled
            //with the lastTransitionChar (which points to the last targetNode) to the last clone.
            if (lastClonedNode != null) {
                clonedNode.reassignOutgoingTransition(lastTransitionLabelChar, lastTargetNode, lastClonedNode);
                lastTargetNode = currentTargetNode;
            }

            //Store clonedNode and the char which labels the transition between the node it was cloned from (currentTargetNode) and THAT node's parent.
            //These will be used to establish an equivalent transition to clonedNode from the next clone to be created (it's clone parent).
            lastClonedNode = clonedNode;
            lastTransitionLabelChar = i > 0 ? str.charAt(i - 1) : '\0';
        }
    }
    
    /**
     * Adds a String to the MDAG (called by addString to do actual MDAG manipulation).
     
     * @param str       the String to be added to the MDAG
     * @return true if and only if MDAG has changed as a result of this call
     */
    private boolean addStringInternal(String str) {
        String prefixString = determineLongestPrefixInMDAG(str);
        String suffixString = str.substring(prefixString.length());

        //Retrive the data related to the first confluence node (a node with two or more incoming transitions)
        //in the transition path from sourceNode corresponding to prefixString.
        HashMap<String, Object> firstConfluenceNodeDataHashMap = getTransitionPathFirstConfluenceNodeData(sourceNode, prefixString);
        MDAGNode firstConfluenceNodeInPrefix = (MDAGNode)firstConfluenceNodeDataHashMap.get("confluenceNode");
        Integer toFirstConfluenceNodeTransitionCharIndex = (Integer) firstConfluenceNodeDataHashMap.get("toConfluenceNodeTransitionCharIndex");
        
        //Remove the register entries of all the nodes in the prefixString transition path up to the first confluence node
        //(those past the confluence node will not need to be removed since they will be cloned and unaffected by the
        //addition of suffixString). If there is no confluence node in prefixString, then remove the register entries in prefixString's entire transition path
        removeTransitionPathRegisterEntries(toFirstConfluenceNodeTransitionCharIndex == null ? prefixString : prefixString.substring(0, toFirstConfluenceNodeTransitionCharIndex));
                
        //If there is a confluence node in the prefix, we must duplicate the transition path
        //of the prefix starting from that node, before we add suffixString (to the duplicate path).
        //This ensures that we do not disturb the other transition paths containing this node.
        if (firstConfluenceNodeInPrefix != null) {
            String transitionStringOfPathToFirstConfluenceNode = prefixString.substring(0, toFirstConfluenceNodeTransitionCharIndex + 1);
            String transitionStringOfToBeDuplicatedPath = prefixString.substring(toFirstConfluenceNodeTransitionCharIndex + 1);
            cloneTransitionPath(firstConfluenceNodeInPrefix, transitionStringOfPathToFirstConfluenceNode, transitionStringOfToBeDuplicatedPath);
        }
        
        //Add the transition based on suffixString to the end of the (possibly duplicated) transition path corresponding to prefixString
        return addTransitionPath(sourceNode.transition(prefixString), suffixString);
    }

    /**
     * Creates a SimpleMDAGNode version of an MDAGNode's outgoing transition set in mdagDataArray.
     
     * @param node                                      the MDAGNode containing the transition set to be inserted in to {@code mdagDataArray}
     * @param mdagDataArray                             an array of SimpleMDAGNodes containing a subset of the data of the MDAG
     * @param onePastLastCreatedConnectionSetIndex      an int of the index in {@code mdagDataArray} that the outgoing transition set of {@code node} is to start from
     * @return                                          an int of one past the end of the transition set located farthest in {@code mdagDataArray}
     */
    private int createSimpleMDAGTransitionSet(MDAGNode node, SimpleMDAGNode[] mdagDataArray, int onePastLastCreatedTransitionSetIndex) {
        int pivotIndex = onePastLastCreatedTransitionSetIndex;
        node.setTransitionSetBeginIndex(pivotIndex);
        
        onePastLastCreatedTransitionSetIndex += node.getOutgoingTransitionCount();

        //Create a SimpleMDAGNode representing each transition label/target combo in transitionTreeMap, recursively calling this method (if necessary)
        //to set indices in these SimpleMDAGNodes that the set of transitions emitting from their respective transition targets starts from.
        TreeMap<Character, MDAGNode> transitionTreeMap = node.getOutgoingTransitions();
        for (Entry<Character, MDAGNode> transitionKeyValuePair : transitionTreeMap.entrySet()) {
            //Use the current transition's label and target node to create a SimpleMDAGNode
            //(which is a space-saving representation of the transition), and insert it in to mdagDataArray
            char transitionLabelChar = transitionKeyValuePair.getKey();
            MDAGNode transitionTargetNode = transitionKeyValuePair.getValue();
            mdagDataArray[pivotIndex] = new SimpleMDAGNode(transitionLabelChar, transitionTargetNode.isAcceptNode(), transitionTargetNode.getOutgoingTransitionCount());
            
            //If targetTransitionNode's outgoing transition set hasn't been inserted in to mdagDataArray yet, call this method on it to do so.
            //After this call returns, transitionTargetNode will contain the index in mdagDataArray that its transition set starts from
            if (transitionTargetNode.getTransitionSetBeginIndex() == -1)
                onePastLastCreatedTransitionSetIndex = createSimpleMDAGTransitionSet(transitionTargetNode, mdagDataArray, onePastLastCreatedTransitionSetIndex);
            
            mdagDataArray[pivotIndex++].setTransitionSetBeginIndex(transitionTargetNode.getTransitionSetBeginIndex());
        }
        
        return onePastLastCreatedTransitionSetIndex;
    }
    
    /**
     * Creates a space-saving version of the MDAG in the form of an array.
     * Once the MDAG is simplified, Strings can no longer be added to or removed from it.
     */
    public void simplify() {
        if (sourceNode != null) {
            mdagDataArray = new SimpleMDAGNode[transitionCount];
            createSimpleMDAGTransitionSet(sourceNode, mdagDataArray, 0);
            simplifiedSourceNode = new SimpleMDAGNode('\0', false, sourceNode.getOutgoingTransitionCount());

            //Mark the previous MDAG data structure and equivalenceClassMDAGNodeHashMap
            //for garbage collection since they are no longer needed.
            sourceNode = null;
            equivalenceClassMDAGNodeHashMap = null;
        }
    }
    
    /**
     * Determines whether a String is present in the MDAG.
     
     * @param str       the String to be searched for
     * @return          true if {@code str} is present in the MDAG, and false otherwise
     */
    public boolean contains(String str) {
        //if the MDAG hasn't been simplified
        if (sourceNode != null) {
            MDAGNode targetNode = sourceNode.transition(str);
            return targetNode != null && targetNode.isAcceptNode();
        } else {
            SimpleMDAGNode targetNode = SimpleMDAGNode.traverseMDAG(mdagDataArray, simplifiedSourceNode, str);
            return targetNode != null && targetNode.isAcceptNode();
        }
    }
    
    /**
     * Retrieves Strings corresponding to all valid transition paths from a given node that satisfy a given condition.
     
     * @param strNavigableSet                a NavigableSet of Strings to contain all those in the MDAG satisfying
     *                                  {@code searchCondition} with {@code conditionString}
     * @param searchCondition           the SearchCondition enum field describing the type of relationship that Strings contained in the MDAG
     *                                  must have with {@code conditionString} in order to be included in the result set
     * @param searchConditionString     the String that all Strings in the MDAG must be related with in the fashion denoted
     *                                  by {@code searchCondition} in order to be included in the result set
     * @param prefixString              the String corresponding to the currently traversed transition path
     * @param transitionTreeMap         a TreeMap of Characters to MDAGNodes collectively representing an MDAGNode's transition set
     */
    private void getStrings(NavigableSet<String> strNavigableSet, SearchCondition searchCondition, String searchConditionString, String prefixString, TreeMap<Character, MDAGNode> transitionTreeMap) {
        //Traverse all the valid transition paths beginning from each transition in transitionTreeMap, inserting the
        //corresponding Strings in to strNavigableSet that have the relationship with conditionString denoted by searchCondition
        for (Entry<Character, MDAGNode> transitionKeyValuePair : transitionTreeMap.entrySet()) {
            String newPrefixString = prefixString + transitionKeyValuePair.getKey();
            MDAGNode currentNode = transitionKeyValuePair.getValue();

            if (currentNode.isAcceptNode() && searchCondition.satisfiesCondition(newPrefixString, searchConditionString))
                strNavigableSet.add(newPrefixString);
            
            //Recursively call this to traverse all the valid transition paths from currentNode
            getStrings(strNavigableSet, searchCondition, searchConditionString, newPrefixString, currentNode.getOutgoingTransitions());
        }
    }
    
    /**
     * Retrieves Strings corresponding to all valid transition paths from a given node that satisfy a given condition.
     
     * @param strNavigableSet                    a NavigableSet of Strings to contain all those in the MDAG satisfying
     *                                      {@code searchCondition} with {@code conditionString}
     * @param searchCondition               the SearchCondition enum field describing the type of relationship that Strings contained in the MDAG
     *                                      must have with {@code conditionString} in order to be included in the result set
     * @param searchConditionString         the String that all Strings in the MDAG must be related with in the fashion denoted
     *                                      by {@code searchCondition} in order to be included in the result set
     * @param prefixString                  the String corresponding to the currently traversed transition path
     * @param transitionSetBegin            an int denoting the starting index of a SimpleMDAGNode's transition set in mdagDataArray
     * @param onePastTransitionSetEnd       an int denoting one past the last index of a simpleMDAGNode's transition set in mdagDataArray
     */
    private void getStrings(NavigableSet<String> strNavigableSet, SearchCondition searchCondition, String searchConditionString, String prefixString, SimpleMDAGNode node) {
        int transitionSetBegin = node.getTransitionSetBeginIndex();
        int onePastTransitionSetEnd = transitionSetBegin +  node.getOutgoingTransitionSetSize();
        
        //Traverse all the valid transition paths beginning from each transition in transitionTreeMap, inserting the
        //corresponding Strings in to strNavigableSet that have the relationship with conditionString denoted by searchCondition
        for (int i = transitionSetBegin; i < onePastTransitionSetEnd; i++) {
            SimpleMDAGNode currentNode = mdagDataArray[i];
            String newPrefixString = prefixString + currentNode.getLetter();
            
            if (currentNode.isAcceptNode() && searchCondition.satisfiesCondition(newPrefixString, searchConditionString))
                strNavigableSet.add(newPrefixString);
            
            //Recursively call this to traverse all the valid transition paths from currentNode
            getStrings(strNavigableSet, searchCondition, searchConditionString, newPrefixString, currentNode);
        }
    }
    
    /**
     * Retrieves all the valid Strings that have been inserted in to the MDAG.
     
     * @return      a NavigableSet containing all the Strings that have been inserted into the MDAG
     */
    public NavigableSet<String> getAllStrings() {
        NavigableSet<String> strNavigableSet = new TreeSet<>();
        
        if (sourceNode != null)
            getStrings(strNavigableSet, SearchCondition.NO_SEARCH_CONDITION, null, "", sourceNode.getOutgoingTransitions());
        else
            getStrings(strNavigableSet, SearchCondition.NO_SEARCH_CONDITION, null, "", simplifiedSourceNode);
        
        return strNavigableSet;
    }
    
    /**
     * Retrieves all the Strings in the MDAG that begin with a given String.
     
     * @param prefixStr     a String that is the prefix for all the desired Strings
     * @return              a NavigableSet containing all the Strings present in the MDAG that begin with {@code prefixString}
     */
    public NavigableSet<String> getStringsStartingWith(String prefixStr) {
        NavigableSet<String> strNavigableSet = new TreeSet<>();
        
        //if the MDAG hasn't been simplified
        if (sourceNode != null) {
            MDAGNode originNode = sourceNode.transition(prefixStr);  //attempt to transition down the path denoted by prefixStr
            
            //if there a transition path corresponding to prefixString (one or more stored Strings begin with prefixString)
            if (originNode != null) {
                if (originNode.isAcceptNode())
                    strNavigableSet.add(prefixStr);
                getStrings(strNavigableSet, SearchCondition.PREFIX_SEARCH_CONDITION, prefixStr, prefixStr, originNode.getOutgoingTransitions());   //retrieve all Strings that extend the transition path denoted by prefixStr
            }
        } else {
            SimpleMDAGNode originNode = SimpleMDAGNode.traverseMDAG(mdagDataArray, simplifiedSourceNode, prefixStr);      //attempt to transition down the path denoted by prefixStr
            
            //if there a transition path corresponding to prefixString (one or more stored Strings begin with prefixStr)
            if (originNode != null) {
                if (originNode.isAcceptNode())
                    strNavigableSet.add(prefixStr);
                getStrings(strNavigableSet, SearchCondition.PREFIX_SEARCH_CONDITION, prefixStr, prefixStr, originNode);        //retrieve all Strings that extend the transition path denoted by prefixString
            }
        }
        
        return strNavigableSet;
    }
    
    /**
     * Retrieves all the Strings in the MDAG that contain a given String.
     
     * @param str       a String that is contained in all the desired Strings
     * @return          a NavigableSet containing all the Strings present in the MDAG that begin with {@code prefixString}
     */
    public NavigableSet<String> getStringsWithSubstring(String str) {
        NavigableSet<String> strNavigableSet = new TreeSet<>();
         
        if (sourceNode != null)      //if the MDAG hasn't been simplified
            getStrings(strNavigableSet, SearchCondition.SUBSTRING_SEARCH_CONDITION, str, "", sourceNode.getOutgoingTransitions());
        else
            getStrings(strNavigableSet, SearchCondition.SUBSTRING_SEARCH_CONDITION, str, "", simplifiedSourceNode);
            
        return strNavigableSet;
    }
    
     /**
     * Retrieves all the Strings in the MDAG that begin with a given String.
     
     * @param suffixStr         a String that is the suffix for all the desired Strings
     * @return                  a NavigableSet containing all the Strings present in the MDAG that end with {@code suffixStr}
     */
    public NavigableSet<String> getStringsEndingWith(String suffixStr) {
        NavigableSet<String> strNavigableSet = new TreeSet<>();
        
        if (sourceNode != null)      //if the MDAG hasn't been simplified
            getStrings(strNavigableSet, SearchCondition.SUFFIX_SEARCH_CONDITION, suffixStr, "", sourceNode.getOutgoingTransitions());
        else
            getStrings(strNavigableSet, SearchCondition.SUFFIX_SEARCH_CONDITION, suffixStr, "", simplifiedSourceNode);

         return strNavigableSet;
    }
    
    /**
     * Returns the MDAG's source node.
    
     * @return      the MDAGNode or SimpleMDAGNode functioning as the MDAG's source node.
     */
    Object getSourceNode() {
        return sourceNode != null ? sourceNode : simplifiedSourceNode;
    }
    
    /**
     * Procures the set of characters which collectively label the MDAG's transitions.
     
     * @return      a TreeSet of chars which collectively label all the transitions in the MDAG
     */
    public TreeSet<Character> getTransitionLabelSet() {
        return charTreeSet;
    }
    
    private int countNodes(MDAGNode originNode, HashSet<Integer> nodeIDHashSet) {
        if (originNode != sourceNode)
            nodeIDHashSet.add(originNode.getId());
        
        TreeMap<Character, MDAGNode> transitionTreeMap = originNode.getOutgoingTransitions();
        
        for (Entry<Character, MDAGNode> transitionKeyValuePair : transitionTreeMap.entrySet())
            countNodes(transitionKeyValuePair.getValue(), nodeIDHashSet);

        return nodeIDHashSet.size();
    }
    
    public int getNodeCount() {
        return countNodes(sourceNode, new HashSet<Integer>());
    }
    
    public int getEquivalenceClassCount() {
        return equivalenceClassMDAGNodeHashMap.size();
    }
    
    public int getTransitionCount() {
        return transitionCount;
    }
    
    public int size() {
        return size;
    }
  
    public String toGraphViz(boolean withNodeIds) {
        StringBuilder dot = new StringBuilder("digraph dawg {\n");
        dot.append("graph [rankdir=LR, ratio=fill];\n");
        dot.append("node [fontsize=14, shape=circle];\n");
        dot.append("edge [fontsize=12];\n");
        Deque<MDAGNode> stack = new LinkedList<>();
        BitSet visited = new BitSet();
        stack.add(sourceNode);
        visited.set(sourceNode.getId());
        while (true) {
            MDAGNode node = stack.pollLast();
            if (node == null)
                break;
            dot.append('n').append(node.getId()).append(" [label=\"").append(node.isAcceptNode() ? 'O' : ' ').append('\"');
            if (withNodeIds) {
                dot.append(", xlabel=\"");
                if (node.getId() == 0)
                    dot.append("START");
                else
                    dot.append(node.getId());
                dot.append('\"');
            }
            dot.append("];\n");
            for (Map.Entry<Character, MDAGNode> e : node.getOutgoingTransitions().entrySet()) {
                MDAGNode nextNode = e.getValue();
                dot.append('n').append(node.getId()).append(" -> n").append(nextNode.getId()).append(" [label=\"").append(e.getKey()).append("\"];\n");
                if (!visited.get(nextNode.getId())) {
                    stack.addLast(nextNode);
                    visited.set(nextNode.getId());
                }
            }
        }
        dot.append('}');
        return dot.toString();
    }

    public void saveAsImage(boolean withNodeIds) throws IOException {
        String graphViz = toGraphViz(withNodeIds);
        Path dotFile = Files.createTempFile("dawg", ".dot");
        Files.write(dotFile, graphViz.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Path dir = Paths.get("temp");
        if (!Files.exists(dir))
            dir = Files.createDirectory(dir);
        Path imageFile = Files.createTempFile(dir, "dawg" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssn")), ".png");
        ProcessBuilder pb = new ProcessBuilder("C:\\Program Files\\GraphViz\\bin\\dot.exe", "-Tpng", dotFile.toFile().getAbsolutePath(), "-o", imageFile.toFile().getAbsolutePath());
        try {
            pb.start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Files.deleteIfExists(dotFile);
        }
    }
}