/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.pig.ExecType;
import org.apache.pig.FuncSpec;
import org.apache.pig.IndexableLoadFunc;
import org.apache.pig.LoadFunc;
import org.apache.pig.PigException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.datastorage.ConfigurationUtil;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigMapReduce;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhyPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataType;
import org.apache.pig.data.InternalCachedBag;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.impl.util.IdentityHashSet;
import org.apache.pig.impl.util.Pair;
import org.apache.pig.pen.util.ExampleTuple;
import org.apache.pig.pen.util.LineageTracer;

public class POMergeCogroup extends PhysicalOperator {

    private static final long serialVersionUID = 1L;

    private transient List<LoadFunc> sideLoaders;

    private List<FuncSpec> sidFuncSpecs;

    private List<String> sideFileSpecs;

    // Local Rearranges corresponding to side Loader to extract relevant bits
    // out of the tuple we get from side loaders.
    private POLocalRearrange LRs[];

    private transient boolean firstTime;

    private transient Comparable<Object> firstKeyOfNextSplit;

    // This is the count of all the relations involved in Cogroup. The Mapped
    // relation is also included in the count.
    private transient int relationCnt;

    private transient TupleFactory mTupleFactory;

    private String indexFileName;

    private FuncSpec idxFuncSpec; 

    private transient DataBag[] outBags;

    private transient Tuple prevTopOfHeap;

    private List<String> loaderSignatures;

    private transient boolean createNewBags;

    // Heap contains tuples with exactly three fields, which is same as output
    // of LR except for third field which contains full-fledged input tuple,
    // as oppose to key-stripped tuple generated by LR.
    private transient PriorityQueue<Tuple> heap;

    private transient boolean lastTime;

    // Need to know in each getNext() whether we generated valid output in last
    // call or not.
    private transient boolean workingOnNewKey;

    public POMergeCogroup(OperatorKey k,List<PhysicalOperator> inpPOs, 
            POLocalRearrange[] lrs, int parallel) {

        super(k,parallel,inpPOs);
        this.LRs = lrs;
        for(int i=0; i < lrs.length; i++)
            LRs[i].setStripKeyFromValue(false);
    }

    @Override
    public Result getNext(Tuple t) throws ExecException {

        try{
            if(createNewBags){      
                // This implies we just generated output tuple in last call.
                // So, its time to create new bags.
                for (int i=0; i < relationCnt; i++)
                    outBags[i] = new InternalCachedBag(relationCnt);
                createNewBags = false;
            }

            // Get the tuple from predecessor.
            Result baseInp = processInput();
            Tuple rearranged;

            switch (baseInp.returnStatus) {

            case POStatus.STATUS_OK:
                rearranged = applyLRon((Tuple)baseInp.result, 0);
                break;

            case POStatus.STATUS_EOP:
                if(!this.parentPlan.endOfAllInput)
                    return baseInp;

                if(lastTime)
                    return baseInp; 

                // We got all the records from mapper but have not yet generated
                // all the outputs that we need to, so we continue filling 
                // and draining the heap from side loaders.
                while(!heap.isEmpty()){

                    Tuple topOfHeap = heap.peek();

                    if(needToBreak(topOfHeap, prevTopOfHeap))
                        break;

                    workingOnNewKey = false;
                    topOfHeap = heap.poll();
                    byte relIdx = (Byte)topOfHeap.get(0);
                    prevTopOfHeap = topOfHeap;
                    outBags[relIdx].add((Tuple)topOfHeap.get(2));

                    if(relIdx == 0) // We got all the mapper has to offer.
                        continue;

                    Tuple nxtTuple = sideLoaders.get(relIdx-1).getNext();
                    if(nxtTuple == null)
                        continue;

                    Tuple rearrangedTup = applyLRon(nxtTuple, relIdx);
                    Object key = rearrangedTup.get(1);
                    if(null == firstKeyOfNextSplit || null == key || firstKeyOfNextSplit.compareTo(key) > 0){
                        heap.offer(rearrangedTup);
                    }
                }

                // This is the last output we will produce, if heap is empty
                // or we got ahead of the first key of the next split.
                if(heap.isEmpty() || (firstKeyOfNextSplit != null && firstKeyOfNextSplit.compareTo(heap.peek().get(1)) <= 0))
                    lastTime = true;

                return getOutputTuple();

            default: // In case of errors, we return the tuple as it is.
                return baseInp;
            }

            // Every time we read something valid from mapper, we add it to heap.
            heap.offer(rearranged);

            if(firstTime){
                setup(rearranged);
                firstTime = false;
                // Heap is initialized with first record from all the relations.
            }

            // Algorithm is as following:

            /* while (there are records in heap) {
                peek at top record from heap
                if we see a key change from previous run, break to return output.
                else, 
                pull tuple from top of heap and place it in bag based on which input it came from
                pull record from input that last record from top of heap came from
                if (key pulled < first key in next split) insert into heap
                }
                output final record
             */

            while(!heap.isEmpty()){

                Tuple topOfHeap = heap.peek();

                if(needToBreak(topOfHeap, prevTopOfHeap))
                    break;

                workingOnNewKey = false;
                topOfHeap = heap.poll();
                byte relIdx = (Byte)topOfHeap.get(0);
                prevTopOfHeap = topOfHeap;
                outBags[relIdx].add((Tuple)topOfHeap.get(2));

                // Pull tuple from the corresponding loader.
                if(relIdx == 0){
                    Tuple tuple = heap.peek();
                    /* At this point, its possible to return the output tuple.
                     * So, lets check if we can do so.
                     * Remember, that for tuples having equal keys, tuple from
                     * leftmost relation is considered largest. So, if the tuple
                     * we just peeked from heap is of leftmost relation and has
                     * a different key then the tuple we polled last,  then we can be 
                     * assured that we have seen all the tuples belonging to this
                     * key and thus we can generate output tuple. 
                     * Else, just pull the next tuple from the left relation by
                     * returning EOP.
                     */
                    // First check if there is a tuple at top of heap and is from 
                    // left relation.
                    if( (null != tuple) && (Byte)tuple.get(0) == 0){
                        // Is key null for both tuples.
                        if(prevTopOfHeap.get(1) == null && tuple.get(1) == null){
                            return new Result(POStatus.STATUS_EOP,null); 
                        }
                        // Does key change from non-null to null or from one
                        // non-null to another non-null.
                        if((prevTopOfHeap.get(1) == null && tuple.get(1) != null) || !tuple.get(1).equals(prevTopOfHeap.get(1))){
                            return getOutputTuple();
                        }
                    }
                    // Either top of heap is from different relation or it is
                    // from left relation but having the same key.
                    return new Result(POStatus.STATUS_EOP,null); 
                }
                
                Tuple nxtTuple = sideLoaders.get(relIdx-1).getNext();
                if(nxtTuple == null) // EOF for this relation
                    continue;

                Tuple rearrangedTup = applyLRon(nxtTuple, relIdx);
                Object key = rearrangedTup.get(1);

                if(firstKeyOfNextSplit == null || null == key || firstKeyOfNextSplit.compareTo(key) > 0){
                    heap.offer(rearrangedTup);
                }
            }

            return getOutputTuple();
        }

        catch(IOException ioe){
            throw new ExecException(ioe);
        }
    }

    private Result getOutputTuple() throws ExecException{

        workingOnNewKey = true;
        createNewBags = true;

        Tuple out = mTupleFactory.newTuple(relationCnt+1);

        out.set(0, prevTopOfHeap.get(1));
        for(int i=0; i < relationCnt; i++)
            out.set(i+1,(outBags[i]));

        return new Result(POStatus.STATUS_OK, illustratorMarkup(null, out, -1));        
    }


    private boolean needToBreak(final Tuple curTopOfHeap, final Tuple prevTopOfHeap) throws ExecException{

        if(workingOnNewKey)
            // This implies we just returned an output tuple in previous call
            // so we are now working on a fresh key.
            return false;

        Object curKey = curTopOfHeap.get(1);
        Object prevKey = prevTopOfHeap.get(1);

        if (curKey == null && null == prevKey)
            // If both keys are nulls, check if they are coming from same relation.
            // because nulls from different relations are not considered equal.
            return ! ((Byte)curTopOfHeap.get(0)).equals((Byte)prevTopOfHeap.get(0));


        if(curKey == null || null == prevKey)
            // If only one of them is null, then key has changed from null to non-null.
            return true;

        // Both the keys are non-null.
        return ! curKey.equals(prevKey);
    }


    @SuppressWarnings( "unchecked")
    private void setup(Tuple firstRearrangedTup) throws IOException{

        // Read our own split Index.
        int  curSplitIdx = ((PigSplit)((Context)PigMapReduce.sJobContext).getInputSplit()).getSplitIndex();
        Object firstBaseKey = firstRearrangedTup.get(1);
        List<Pair<Integer,Tuple>> index = readIndex();

        // If we are in last split, firstKeyOfNextSplit is marked as null.
        // Null value of firstKeyOfNextSplit is used to indicate collect all 
        // tuples from both base loaders as well as side loaders 
        // and process them in this map.
        // Note that nulls are smaller then anything else. So, if there are 
        // nulls in data, they will be in very first split. So, null value of 
        // this variable can be used to determine whether we are working in last
        // split or not.

        firstKeyOfNextSplit = getFirstKeyOfNextSplit(curSplitIdx, index); 

        // Open all other streams. 
        // If this is first split, start from very first record. 
        // For all other splits, bind to the first key which is greater
        // then or equal to the first key of the map.

        for(int i=0; i < relationCnt-1; i ++){

            LoadFunc loadfunc = (LoadFunc)PigContext.instantiateFuncFromSpec(sidFuncSpecs.get(i));
            loadfunc.setUDFContextSignature(loaderSignatures.get(i));
            Job dummyJob = new Job(new Configuration(PigMapReduce.sJobConf));
            loadfunc.setLocation(sideFileSpecs.get(i), dummyJob);
            ((IndexableLoadFunc)loadfunc).initialize(dummyJob.getConfiguration());
            sideLoaders.add(loadfunc);
            Tuple rearranged;

            if ( index.get(0).first.equals(curSplitIdx)){ 
                // This is a first split, bind at very first record in all side relations.
                Tuple t = loadfunc.getNext();
                if(null == t)   // This side relation is entirely empty.
                    continue;
                rearranged = applyLRon(t, i+1);
                heap.offer(rearranged);
                continue;
            }
            else{
                // This is not a first split, we need to bind to the key equal 
                // to the firstBaseKey or next key thereafter.

                // First seek close to base key.  
                ((IndexableLoadFunc)loadfunc).seekNear(firstBaseKey instanceof 
                        Tuple ? (Tuple) firstBaseKey : mTupleFactory.newTuple(firstBaseKey));

                // Since contract of IndexableLoadFunc is not clear where we 
                // will land up after seekNear() call,
                // we start reading from side loader to get to the point where key 
                // is actually greater or equal to base key.
                while(true){
                    Tuple t = loadfunc.getNext();
                    if(t==null) // This relation has ended.
                        break;
                    rearranged = applyLRon(t, i+1);
                    if(rearranged.get(1) == null) // If we got a null key here
                        continue;             // it implies we are still behind.

                    int cmpVal = ((Comparable<Object>)rearranged.get(1)).compareTo(firstBaseKey);
                    if(cmpVal >= 0){  // Break away as soon as we get ahead.

                        // Add this tuple in heap only if it needs to be processed in
                        // this map. That is it needs to be smaller then next split's
                        // first key, unless this is the last split, in which case it
                        // will be processed in this map.
                        if(firstKeyOfNextSplit == null || firstKeyOfNextSplit.compareTo(rearranged.get(1)) > 0 ){
                            heap.offer(rearranged);                
                        }                        
                        break;
                    }
                }
            }
        }
    }

    private List<Pair<Integer,Tuple>> readIndex() throws ExecException{

        // Assertions on index we are about to read:
        // We are reading index from a file through POLoad which will return tuples.
        // These tuples looks as follows:
        // (key1, key2, key3,..,WritableComparable(wc),splitIdx(si))
        // Index is sorted on key1, then on key2, key3.. , wc, si.
        // Index contains exactly one entry per split.
        // Since this is loaded through CollectableLoadFunc, keys can't repeat.
        // Thus, each index entry contains unique key.
        // Since nulls are smaller then anything else, if its in data, key with null value 
        // should be very first entry of index.

        // First create a loader to read index.
        POLoad ld = new POLoad(new OperatorKey(this.mKey.scope,NodeIdGenerator.getGenerator().getNextNodeId(this.mKey.scope)), 
                new FileSpec(indexFileName, idxFuncSpec));

        // Index file is distributed through Distributed Cache to all mappers. So, read it locally.
        Properties props = ConfigurationUtil.getLocalFSProperties();
        ld.setPc(new PigContext(ExecType.LOCAL, props));

        // Each index entry is read as a pair of split index and a tuple consisting of key.
        List<Pair<Integer,Tuple>> index = new ArrayList<Pair<Integer,Tuple>>();

        for(Result res=ld.getNext(dummyTuple);res.returnStatus!=POStatus.STATUS_EOP;res=ld.getNext(dummyTuple)){

            Tuple  idxTuple = (Tuple)res.result;
            int colCnt = idxTuple.size()-2;
            Tuple keyTuple = mTupleFactory.newTuple(colCnt);

            for (int i=0; i< colCnt; i++)
                keyTuple.set(i, idxTuple.get(i));

            index.add(new Pair<Integer, Tuple>((Integer)idxTuple.get(colCnt+1), keyTuple));
        }

        return index;
    }

    @SuppressWarnings("unchecked")
    private Comparable<Object> getFirstKeyOfNextSplit(final int curSplitIdx, final List<Pair<Integer,Tuple>> index) throws IOException{

        // First find out the index entry corresponding to our current split.
        int i;
        for(i=0; i < index.size(); i++){
            if(index.get(i).first.equals(curSplitIdx))
                break;
        }

        // Now read key of the very next index entry.
        if(i < index.size()-1){
            Tuple keyTuple =  index.get(i+1).second;
            return keyTuple.size() == 1 ? (Comparable<Object>)keyTuple.get(0) : keyTuple;
        }

        // If we are here it implies, current split is the last split.
        return null;


    }

    private Tuple applyLRon(final Tuple inp, final int lrIdx) throws ExecException{

        //Separate Key & Value of input using corresponding LR operator
        POLocalRearrange lr = LRs[lrIdx];
        lr.attachInput(inp);
        Result lrOut = lr.getNext(dummyTuple);

        if(lrOut.returnStatus!=POStatus.STATUS_OK){
            int errCode = 2167;
            String errMsg = "LocalRearrange used to extract keys from tuple isn't configured correctly";
            throw new ExecException(errMsg,errCode,PigException.BUG);
        } 
        lr.detachInput();
        return mTupleFactory.newTuple(((Tuple)lrOut.result).getAll());
    }

    @Override
    public void visit(PhyPlanVisitor v) throws VisitorException {
        v.visitMergeCoGroup(this);
    }

    @Override
    public String name() {
        return getAliasString() + "MergeCogroup["
                + DataType.findTypeName(resultType) + "]" + " - "
                + mKey.toString();
    }

    @Override
    public boolean supportsMultipleInputs() {
        return true;
    }

    @Override
    public boolean supportsMultipleOutputs() {
        return false;
    }

    public List<PhysicalPlan> getLRInnerPlansOf(int i) {
        return this.LRs[i].getPlans();
    }

    public void setSideLoadFuncs(List<FuncSpec> sideLoadFuncs) {
        this.sidFuncSpecs = sideLoadFuncs;
    }

    public void setSideFileSpecs(List<String> sideFileSpecs) {
        this.sideFileSpecs = sideFileSpecs;
    }

    public String getIndexFileName() {
        return indexFileName;
    }

    public void setIndexFileName(String indexFileName) {
        this.indexFileName = indexFileName;
    }

    public FuncSpec getIdxFuncSpec() {
        return idxFuncSpec;
    }

    public void setIdxFuncSpec(FuncSpec idxFileSpec) {
        this.idxFuncSpec = idxFileSpec;
    }

    public void setLoaderSignatures(List<String> loaderSignatures) {
        this.loaderSignatures = loaderSignatures;
    }

    private void readObject(final ObjectInputStream is) throws IOException,
    ClassNotFoundException, ExecException {

        is.defaultReadObject();
        mTupleFactory = TupleFactory.getInstance();
        this.heap = new PriorityQueue<Tuple>(11, new Comparator<Tuple>() {

            @SuppressWarnings("unchecked")
            @Override
            public int compare(final Tuple resLHS, final Tuple resRHS) {
                try {
                    // First, about null keys.
                    // Java's priority queue doesn't handle null, so we handle it ourselves.

                    // If both keys are null, then keys of side loader is considered smaller.
                    // That is so, because when we poll the heap, we want to get the keys
                    // of side loader first then the keys of base loader.

                    // If null is compared against non-null, null is smaller. 

                    Object leftKey = resLHS.get(1);
                    Object rightKey = resRHS.get(1);

                    if (null == leftKey && null == rightKey){
                        return ((Byte)resRHS.get(0)).compareTo((Byte)resLHS.get(0));
                    }
                    if(null == leftKey)
                        return -1;
                    if(null == rightKey)
                        return 1;

                    // Now, about non-null keys.
                    // Compare the keys which are at index 1 of tuples being
                    // put in heap.                    

                    int cmpval = ((Comparable<Object>)leftKey).compareTo(rightKey);

                    // If keys are equal, tuple from side relations 
                    // are considered smaller. This is so because we want 
                    // to get back tuple of the mapper last when polling tuples
                    // from heap. And index of mapped relation is 0.

                    return cmpval == 0 ? ((Byte)resRHS.get(0)).compareTo((Byte)resLHS.get(0)) : cmpval;
                } catch (ExecException e) {

                    // Alas, no choice but to throw Runtime exception.
                    String errMsg = "Exception occured in compare() of heap in POMergeCogroup.";
                    throw new RuntimeException(errMsg,e);
                }
            } 
        });

        this.createNewBags = true;
        this.lastTime = false;
        this.relationCnt = LRs.length;
        this.outBags = new DataBag[relationCnt];
        this.firstTime = true;
        this.workingOnNewKey = true;
        this.sideLoaders = new ArrayList<LoadFunc>();
    }

    // This function is only for debugging. Call it whenever you want to print
    // the current state of heap.
    int counter = 0;
    private void printHeap(){
        System.out.println("Printing heap :"+ ++counter);
        PriorityQueue<Tuple> copy = new PriorityQueue<Tuple>(heap);
        System.out.println("Heap size: "+heap.size());
        int i =0;
        while(!copy.isEmpty()){
            System.out.println(i+++"th item in heap: "+ copy.poll());
        }
    }
    
    @Override
    public Tuple illustratorMarkup(Object in, Object out, int eqClassIndex) {
        if(illustrator != null) {
            ExampleTuple tOut = new ExampleTuple((Tuple) out);
            LineageTracer lineageTracer = illustrator.getLineage();
            lineageTracer.insert((Tuple) out);
            Tuple tmp;
            boolean synthetic = false;
            try {
                for (int i = 1; i < relationCnt; i++)
                {
                    DataBag dbs = (DataBag) ((Tuple) out).get(i);
                    Iterator<Tuple> iter = dbs.iterator();
                    while (iter.hasNext()) {
                        tmp = iter.next();
                        // any of synthetic data in bags causes the output tuple to be synthetic
                        if (!synthetic && ((ExampleTuple)tmp).synthetic)
                            synthetic = true;
                        lineageTracer.union(tOut, tmp);
                        // TODO constraint of >=2 tuples per eq. class
                        illustrator.getEquivalenceClasses().get(i-1).add(tmp);
                    }
                }
            } catch (ExecException e) {
              // TODO better exception handling
              throw new RuntimeException("Illustrator exception :"+e.getMessage());
            }
            tOut.synthetic = synthetic;
            illustrator.addData((Tuple) tOut);
            return tOut;
        } else
            return (Tuple) out;
    }
}
