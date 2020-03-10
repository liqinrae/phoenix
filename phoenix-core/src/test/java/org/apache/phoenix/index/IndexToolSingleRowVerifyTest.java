/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.index;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.coprocessor.IndexRebuildRegionScanner;
import org.apache.phoenix.coprocessor.IndexToolVerificationResult;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.BaseConnectionlessQueryTest;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.util.EnvironmentEdge;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.SchemaUtil;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;

import static org.apache.phoenix.hbase.index.IndexRegionObserver.UNVERIFIED_BYTES;
import static org.apache.phoenix.hbase.index.IndexRegionObserver.VERIFIED_BYTES;
import static org.apache.phoenix.query.QueryConstants.EMPTY_COLUMN_BYTES;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

public class IndexToolSingleRowVerifyTest extends BaseConnectionlessQueryTest {

    private static final int INDEX_TABLE_EXPIRY_SEC = 1;
    private static final String UNEXPECTED_COLUMN = "UNEXPECTED_COLUMN";
    public static final String FIRST_ID = "FIRST_ID";
    public static final String SECOND_ID = "SECOND_ID";
    public static final String FIRST_VALUE = "FIRST_VALUE";
    public static final String SECOND_VALUE = "SECOND_VALUE";
    public static final String
            createTableDDL = "CREATE TABLE IF NOT EXISTS %s (FIRST_ID BIGINT NOT NULL, "
                        + "SECOND_ID BIGINT NOT NULL, FIRST_VALUE VARCHAR(20), "
                        + "SECOND_VALUE INTEGER "
                        + "CONSTRAINT PK PRIMARY KEY(FIRST_ID, SECOND_ID)) COLUMN_ENCODED_BYTES=0";

    public static final String
            createIndexDDL = "CREATE INDEX %s ON %s (SECOND_VALUE) INCLUDE (FIRST_VALUE)";
    public static final String completeRowUpsert = "UPSERT INTO %s VALUES (?,?,?,?)";
    public static final String partialRowUpsert1 = "UPSERT INTO %s (%s, %s, %s) VALUES (?,?,?)";
    public static final String deleteRowDML = "DELETE FROM %s WHERE %s = ?  AND %s = ?";

    private enum TestType {
        VALID,
        VALID_MIX_MUTATIONS,
        VALID_NEW_UNVERIFIED_MUTATIONS,
        VALID_MORE_MUTATIONS,
        EXPIRED,
        INVALID_EXTRA_CELL,
        INVALID_EMPTY_CELL,
        INVALID_CELL_VALUE,
        INVALID_COLUMN
    }

    public static class UnitTestClock extends EnvironmentEdge {
        long initialTime;
        long delta;

        public UnitTestClock(long delta) {
            initialTime = System.currentTimeMillis() + delta;
            this.delta = delta;
        }

        @Override
        public long currentTime() {
            return System.currentTimeMillis() + delta;
        }
    }

    @Mock
    Result indexRow;
    @Mock
    IndexRebuildRegionScanner rebuildScanner;
    List<Mutation> actualMutationList;
    String schema, table, dataTableFullName, index, indexTableFullName;
    PTable pIndexTable, pDataTable;
    Put put = null;
    Delete delete = null;
    PhoenixConnection pconn;
    IndexToolVerificationResult.PhaseResult actualPR;
    public Map<byte[], List<Mutation>> indexKeyToMutationMapLocal;

    @Before
    public void setup() throws SQLException, IOException {
        MockitoAnnotations.initMocks(this);
        createDBObject();
        createMutationsWithUpserts();
        initializeRebuildScannerAttributes();
        initializeGlobalMockitoSetup();
    }

    public void createDBObject() throws SQLException {
        try(Connection conn = DriverManager.getConnection(getUrl(), new Properties())) {
            schema = generateUniqueName();
            table = generateUniqueName();
            index = generateUniqueName();
            dataTableFullName = SchemaUtil.getQualifiedTableName(schema, table);
            indexTableFullName = SchemaUtil.getQualifiedTableName(schema, index);

            conn.createStatement().execute(String.format(createTableDDL, dataTableFullName));
            conn.createStatement().execute(String.format(createIndexDDL, index, dataTableFullName));
            conn.commit();

            pconn = conn.unwrap(PhoenixConnection.class);
            pIndexTable = pconn.getTable(new PTableKey(pconn.getTenantId(), indexTableFullName));
            pDataTable = pconn.getTable(new PTableKey(pconn.getTenantId(), dataTableFullName));
        }
    }

    private void createMutationsWithUpserts() throws SQLException, IOException {
        deleteRow(2, 3);
        upsertPartialRow(2, 3, "abc");
        upsertCompleteRow(2, 3, "hik", 8);
        upsertPartialRow(2, 3, 10);
        upsertPartialRow(2,3,4);
        deleteRow(2, 3);
        upsertPartialRow(2,3, "def");
        upsertCompleteRow(2, 3, null, 20);
        upsertPartialRow(2,3, "wert");
    }

    private void deleteRow(int key1, int key2) throws SQLException, IOException {
        try(Connection conn = DriverManager.getConnection(getUrl(), new Properties())){
            PreparedStatement ps =
                    conn.prepareStatement(
                            String.format(deleteRowDML, dataTableFullName, FIRST_ID, SECOND_ID));
            ps.setInt(1, key1);
            ps.setInt(2, key2);
            ps.execute();
            convertUpsertToMutations(conn);
        }
    }

    private void upsertPartialRow(int key1, int key2, String val1)
            throws SQLException, IOException {

        try(Connection conn = DriverManager.getConnection(getUrl(), new Properties())){
            PreparedStatement ps =
                    conn.prepareStatement(
                            String.format(partialRowUpsert1, dataTableFullName, FIRST_ID, SECOND_ID,
                                    FIRST_VALUE));
            ps.setInt(1, key1);
            ps.setInt(2, key2);
            ps.setString(3, val1);
            ps.execute();
            convertUpsertToMutations(conn);
        }
    }

    private void upsertPartialRow(int key1, int key2, int value1)
            throws SQLException, IOException {

        try(Connection conn = DriverManager.getConnection(getUrl(), new Properties())){
            PreparedStatement
                    ps =
                    conn.prepareStatement(
                            String.format(partialRowUpsert1, dataTableFullName, FIRST_ID, SECOND_ID,
                                    SECOND_VALUE));
            ps.setInt(1, key1);
            ps.setInt(2, key2);
            ps.setInt(3, value1);
            ps.execute();
            convertUpsertToMutations(conn);
        }
    }

    private void upsertCompleteRow(int key1, int key2, String val1
    , int val2) throws SQLException, IOException {
        try(Connection conn = DriverManager.getConnection(getUrl(), new Properties())) {
            PreparedStatement
                    ps = conn.prepareStatement(String.format(completeRowUpsert, dataTableFullName));
            ps.setInt(1, key1);
            ps.setInt(2, key2);
            ps.setString(3, val1);
            ps.setInt(4, val2);
            ps.execute();
            convertUpsertToMutations(conn);
        }
    }

    private void convertUpsertToMutations(Connection conn) throws SQLException, IOException {
        Iterator<Pair<byte[],List<KeyValue>>>
                dataTableNameAndMutationKeyValuesIter = PhoenixRuntime.getUncommittedDataIterator(conn);
        Pair<byte[], List<KeyValue>> elem = dataTableNameAndMutationKeyValuesIter.next();
        byte[] key = elem.getSecond().get(0).getRow();
        long mutationTS = EnvironmentEdgeManager.currentTimeMillis();

        for (KeyValue kv : elem.getSecond()) {
            Cell cell =
                    CellUtil.createCell(kv.getRow(), kv.getFamily(), kv.getQualifier(),
                            mutationTS, kv.getType(), kv.getValue());
            if (KeyValue.Type.codeToType(cell.getTypeByte()) == KeyValue.Type.Put) {
                if (put == null ) {
                    put = new Put(key);
                }
                put.add(cell);
            } else {
                if (delete == null) {
                    delete = new Delete(key);
                }
                delete.addDeleteMarker(cell);
            }
        }
    }

    private void initializeRebuildScannerAttributes() {
        rebuildScanner.indexTableTTL = HConstants.FOREVER;
        rebuildScanner.indexMaintainer = pIndexTable.getIndexMaintainer(pDataTable, pconn);
        rebuildScanner.indexKeyToMutationMap = Maps.newTreeMap((Bytes.BYTES_COMPARATOR));
    }

    private void initializeGlobalMockitoSetup() throws IOException {
        //setup
        when(rebuildScanner.getIndexRowKey(put)).thenCallRealMethod();
        when(rebuildScanner.prepareIndexMutations(put, delete)).thenCallRealMethod();
        when(rebuildScanner.verifySingleIndexRow(Matchers.<Result>any(),
                Matchers.<IndexToolVerificationResult.PhaseResult>any())).thenCallRealMethod();
        doNothing().when(rebuildScanner)
                .logToIndexToolOutputTable(Matchers.<byte[]>any(),Matchers.<byte[]>any(),
                Mockito.anyLong(),Mockito.anyLong(), Mockito.anyString(),
                        Matchers.<byte[]>any(), Matchers.<byte[]>any());
        doNothing().when(rebuildScanner)
                .logToIndexToolOutputTable(Matchers.<byte[]>any(),Matchers.<byte[]>any(),
                Mockito.anyLong(),Mockito.anyLong(), Mockito.anyString());

        //populate the local map to use to create actual mutations
        indexKeyToMutationMapLocal = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
        rebuildScanner.indexKeyToMutationMap = indexKeyToMutationMapLocal;
        rebuildScanner.prepareIndexMutations(put, delete);

        //populate map to use in test code
        rebuildScanner.indexKeyToMutationMap = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);
        rebuildScanner.prepareIndexMutations(put, delete);
    }

    @Test
    public void testVerifySingleIndexRow_validIndexRowCount_nonZero() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getValidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.VALID);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_validIndexRowCount_moreActual() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getValidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.VALID_MORE_MUTATIONS);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_allMix() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getValidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.VALID_MIX_MUTATIONS);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_allUnverified() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getValidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.VALID_NEW_UNVERIFIED_MUTATIONS);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_expiredIndexRowCount_nonZero() throws IOException {
        IndexToolVerificationResult.PhaseResult
                expectedPR = new IndexToolVerificationResult.PhaseResult(0, 1, 0, 0);
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.EXPIRED);
            expireThisRow();
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_invalidIndexRowCount_cellValue() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getInvalidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.INVALID_CELL_VALUE);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_invalidIndexRowCount_emptyCell() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getInvalidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.INVALID_EMPTY_CELL);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_invalidIndexRowCount_diffColumn() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getInvalidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.INVALID_COLUMN);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_invalidIndexRowCount_extraCell() throws IOException {
        IndexToolVerificationResult.PhaseResult expectedPR = getInvalidPhaseResult();
        for (Map.Entry<byte[], List<Mutation>>
                entry : indexKeyToMutationMapLocal.entrySet()) {
            initializeLocalMockitoSetup(entry, TestType.INVALID_EXTRA_CELL);
            //test code
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
            //assert
            assertVerificationPhaseResult(actualPR, expectedPR);
        }
    }

    @Test
    public void testVerifySingleIndexRow_expectedMutations_null() throws IOException {
        when(indexRow.getRow()).thenReturn(Bytes.toBytes(1));
        try {
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
        } catch (DoNotRetryIOException e) {
            Assert.assertTrue(e.getMessage().contains(IndexRebuildRegionScanner.NO_EXPECTED_MUTATION));
        }
    }

    @Test
    public void testVerifySingleIndexRow_actualMutations_null() throws IOException {
        byte[] indexKey = rebuildScanner.getIndexRowKey(put);
        when(indexRow.getRow()).thenReturn(indexKey);
        when(rebuildScanner.prepareActualIndexMutations(indexRow)).thenReturn(null);
        try {
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
        } catch (DoNotRetryIOException e) {
            Assert.assertTrue(e.getMessage().contains(IndexRebuildRegionScanner.ACTUAL_MUTATION_IS_NULL_OR_EMPTY));
        }
    }

    @Test
    public void testVerifySingleIndexRow_actualMutations_empty() throws IOException {
        byte[] indexKey = rebuildScanner.getIndexRowKey(put);
        when(indexRow.getRow()).thenReturn(indexKey);
        actualMutationList = new ArrayList<>();
        when(rebuildScanner.prepareActualIndexMutations(indexRow)).thenReturn(actualMutationList);
        try {
            rebuildScanner.verifySingleIndexRow(indexRow, actualPR);
        } catch (DoNotRetryIOException e) {
            Assert.assertTrue(e.getMessage().contains(IndexRebuildRegionScanner.ACTUAL_MUTATION_IS_NULL_OR_EMPTY));
        }
    }

    private IndexToolVerificationResult.PhaseResult getValidPhaseResult() {
        return new IndexToolVerificationResult.PhaseResult(1,0,0,0);
    }

    private IndexToolVerificationResult.PhaseResult getInvalidPhaseResult() {
        return new IndexToolVerificationResult.PhaseResult(0, 0, 0, 1);
    }

    private void initializeLocalMockitoSetup(Map.Entry<byte[], List<Mutation>> entry,
            TestType testType)
            throws IOException {
        actualPR = new IndexToolVerificationResult.PhaseResult();
        byte[] indexKey = entry.getKey();
        when(indexRow.getRow()).thenReturn(indexKey);
        actualMutationList = buildActualIndexMutationsList(testType);
        when(rebuildScanner.prepareActualIndexMutations(indexRow)).thenReturn(actualMutationList);
    }

    private List<Mutation> buildActualIndexMutationsList(TestType testType) {
        List<Mutation> actualMutations = new ArrayList<>();
        actualMutations.addAll(indexKeyToMutationMapLocal.get(indexRow.getRow()));
        if(testType.equals(TestType.EXPIRED)) {
            return actualMutations;
        }
        if(testType.toString().startsWith("VALID")) {
            return getValidActualMutations(testType, actualMutations);
        }
        if(testType.toString().startsWith("INVALID")) {
            return getInvalidActualMutations(testType, actualMutations);
        }
        return null;
    }

    private List <Mutation> getValidActualMutations(TestType testType,
            List<Mutation> actualMutations) {
        List <Mutation> newActualMutations = new ArrayList<>();
        if(testType.equals(TestType.VALID)) {
            return actualMutations;
        }
        if (testType.equals(TestType.VALID_MIX_MUTATIONS)) {
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), null));
            newActualMutations.add(getDeleteMutation(actualMutations.get(0), new Long(1)));
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), null));
        }
        if (testType.equals(TestType.VALID_NEW_UNVERIFIED_MUTATIONS)) {
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), null));
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), null));
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), null));
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), new Long(1)));
        }
        newActualMutations.addAll(actualMutations);
        if(testType.equals(TestType.VALID_MORE_MUTATIONS)) {
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), null));
            newActualMutations.add(getDeleteMutation(actualMutations.get(0), null));
            newActualMutations.add(getDeleteMutation(actualMutations.get(0), new Long(1)));
            newActualMutations.add(getUnverifiedPutMutation(actualMutations.get(0), new Long(1)));
        }
        return newActualMutations;
    }

    private List <Mutation> getInvalidActualMutations(TestType testType,
            List<Mutation> actualMutations) {
        List <Mutation> newActualMutations = new ArrayList<>();
        newActualMutations.addAll(actualMutations);
        for (Mutation m : actualMutations) {
            newActualMutations.remove(m);
            NavigableMap<byte[], List<Cell>> familyCellMap = m.getFamilyCellMap();
            List<Cell> cellList = familyCellMap.firstEntry().getValue();
            List<Cell> newCellList = new ArrayList<>();
            byte[] fam = CellUtil.cloneFamily(cellList.get(0));
            for (Cell c : cellList) {
                infiltrateCell(c, newCellList, testType);
            }
            familyCellMap.put(fam, newCellList);
            m.setFamilyCellMap(familyCellMap);
            newActualMutations.add(m);
        }
        return newActualMutations;
    }

    private void infiltrateCell(Cell c, List<Cell> newCellList, TestType e) {
        Cell newCell;
        Cell emptyCell;
        switch(e) {
        case INVALID_COLUMN:
            newCell =
                    CellUtil.createCell(CellUtil.cloneRow(c), CellUtil.cloneFamily(c),
                            Bytes.toBytes("0:" + UNEXPECTED_COLUMN),
                            EnvironmentEdgeManager.currentTimeMillis(),
                            KeyValue.Type.Put.getCode(), Bytes.toBytes("zxcv"));
            newCellList.add(newCell);
            newCellList.add(c);
            break;
        case INVALID_CELL_VALUE:
            if (CellUtil.matchingQualifier(c, EMPTY_COLUMN_BYTES)) {
                newCell = getCellWithPut(c);
                emptyCell = getUnverifiedEmptyCell(c);
                newCellList.add(newCell);
                newCellList.add(emptyCell);
            } else {
                newCellList.add(c);
            }
            break;
        case INVALID_EMPTY_CELL:
            if (CellUtil.matchingQualifier(c, EMPTY_COLUMN_BYTES)) {
                newCell =
                        CellUtil.createCell(CellUtil.cloneRow(c), CellUtil.cloneFamily(c),
                                CellUtil.cloneQualifier(c), c.getTimestamp(),
                                KeyValue.Type.Delete.getCode(), VERIFIED_BYTES);
                newCellList.add(newCell);
            } else {
                newCellList.add(c);
            }
            break;
        case INVALID_EXTRA_CELL:
            if (CellUtil.matchingQualifier(c, EMPTY_COLUMN_BYTES)) {
                newCell = getCellWithPut(c);
                emptyCell = getUnverifiedEmptyCell(c);
                newCellList.add(newCell);
                newCellList.add(emptyCell);
            }
            newCellList.add(c);
        }
    }

    private Cell getUnverifiedEmptyCell(Cell c) {
        return CellUtil.createCell(CellUtil.cloneRow(c), CellUtil.cloneFamily(c),
                rebuildScanner.indexMaintainer.getEmptyKeyValueQualifier(),
                EnvironmentEdgeManager.currentTimeMillis(),
                KeyValue.Type.Put.getCode(), UNVERIFIED_BYTES);
    }

    private Cell getCellWithPut(Cell c) {
        return CellUtil.createCell(CellUtil.cloneRow(c),
                CellUtil.cloneFamily(c), Bytes.toBytes("0:FIRST_VALUE"),
                EnvironmentEdgeManager.currentTimeMillis(), KeyValue.Type.Put.getCode(),
                Bytes.toBytes("zxcv"));
    }

    private void expireThisRow() {
        rebuildScanner.indexTableTTL = INDEX_TABLE_EXPIRY_SEC;
        UnitTestClock expiryClock = new UnitTestClock(5000);
        EnvironmentEdgeManager.injectEdge(expiryClock);
    }

    private Mutation getDeleteMutation(Mutation orig, Long ts) {
        Mutation m = new Delete(orig.getRow());
        List<Cell> origList = orig.getFamilyCellMap().firstEntry().getValue();
        ts = ts == null ? EnvironmentEdgeManager.currentTimeMillis() : ts;
        Cell c = CellUtil.createCell(orig.getRow(),
                CellUtil.cloneFamily(origList.get(0)), Bytes.toBytes("0:FIRST_VALUE"),
                ts, KeyValue.Type.DeleteFamilyVersion.getCode(), Bytes.toBytes("asdfg"));
        Cell empty = CellUtil.createCell(orig.getRow(), CellUtil.cloneFamily(origList.get(0)),
                rebuildScanner.indexMaintainer.getEmptyKeyValueQualifier(),
                ts, KeyValue.Type.DeleteFamilyVersion.getCode(), VERIFIED_BYTES);
        byte[] fam = CellUtil.cloneFamily(origList.get(0));
        List<Cell> famCells = Lists.newArrayList();
        m.getFamilyCellMap().put(fam, famCells);
        famCells.add(c);
        famCells.add(empty);
        return m;
    }

    private Mutation getUnverifiedPutMutation(Mutation orig, Long ts) {
        Mutation m = new Put(orig.getRow());
        if (orig.getAttributesMap() != null) {
            for (Map.Entry<String,byte[]> entry : orig.getAttributesMap().entrySet()) {
                m.setAttribute(entry.getKey(), entry.getValue());
            }
        }
        List<Cell> origList = orig.getFamilyCellMap().firstEntry().getValue();
        ts = ts == null ? EnvironmentEdgeManager.currentTimeMillis() : ts;
        Cell c = CellUtil.createCell(orig.getRow(),
                CellUtil.cloneFamily(origList.get(0)), Bytes.toBytes("0:FIRST_VALUE"),
                ts, KeyValue.Type.Put.getCode(), Bytes.toBytes("asdfg"));
        Cell empty = CellUtil.createCell(orig.getRow(), CellUtil.cloneFamily(origList.get(0)),
                rebuildScanner.indexMaintainer.getEmptyKeyValueQualifier(),
                ts, KeyValue.Type.Put.getCode(), UNVERIFIED_BYTES);
        byte[] fam = CellUtil.cloneFamily(origList.get(0));
        List<Cell> famCells = Lists.newArrayList();
        m.getFamilyCellMap().put(fam, famCells);
        famCells.add(c);
        famCells.add(empty);
        return m;
    }

    private void assertVerificationPhaseResult(IndexToolVerificationResult.PhaseResult actualPR,
            IndexToolVerificationResult.PhaseResult expectedPR) {
        Assert.assertTrue(actualPR.toString().equalsIgnoreCase(expectedPR.toString()));
    }
}
