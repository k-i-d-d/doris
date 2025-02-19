// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.datasource;

import org.apache.doris.analysis.AlterCatalogNameStmt;
import org.apache.doris.analysis.AlterCatalogPropertyStmt;
import org.apache.doris.analysis.CreateCatalogStmt;
import org.apache.doris.analysis.DropCatalogStmt;
import org.apache.doris.analysis.ShowCatalogStmt;
import org.apache.doris.catalog.Catalog;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.UserException;
import org.apache.doris.common.io.Text;
import org.apache.doris.common.io.Writable;
import org.apache.doris.persist.OperationType;
import org.apache.doris.persist.gson.GsonUtils;
import org.apache.doris.qe.ShowResultSet;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * DataSourceMgr will load all data sources at FE startup,
 * and save them in map with name.
 * Note: Catalog in sql syntax will be treated as  datasource interface in code level.
 * TODO: Change the package name into catalog.
 */
public class DataSourceMgr implements Writable {
    private static final Logger LOG = LogManager.getLogger(DataSourceMgr.class);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private final Map<String, DataSourceIf> nameToCatalogs = Maps.newConcurrentMap();

    // Use a separate instance to facilitate access.
    // internalDataSource still exists in idToDataSource and nameToDataSource
    private InternalDataSource internalDataSource;

    public DataSourceMgr() {
        initInternalDataSource();
    }

    private void initInternalDataSource() {
        internalDataSource = new InternalDataSource();
        nameToCatalogs.put(internalDataSource.getName(), internalDataSource);
    }

    public InternalDataSource getInternalDataSource() {
        return internalDataSource;
    }

    private void writeLock() {
        lock.writeLock().lock();
    }

    private void writeUnlock() {
        lock.writeLock().unlock();
    }

    private void readLock() {
        lock.readLock().lock();
    }

    private void readUnlock() {
        lock.readLock().unlock();
    }

    /**
     * Create and hold the catalog instance and write the meta log.
     */
    public void createCatalog(CreateCatalogStmt stmt) throws UserException {
        if (stmt.isSetIfNotExists() && nameToCatalogs.containsKey(stmt.getCatalogName())) {
            LOG.warn("Catalog {} is already exist.", stmt.getCatalogName());
            return;
        }
        if (nameToCatalogs.containsKey(stmt.getCatalogName())) {
            throw new DdlException("Catalog had already exist with name: " + stmt.getCatalogName());
        }
        CatalogLog log = CatalogFactory.constructorCatalogLog(stmt);
        replayCreateCatalog(log);
        Catalog.getCurrentCatalog().getEditLog().logDatasourceLog(OperationType.OP_CREATE_DS, log);
    }

    /**
     * Remove the catalog instance by name and write the meta log.
     */
    public void dropCatalog(DropCatalogStmt stmt) throws UserException {
        if (stmt.isSetIfExists() && !nameToCatalogs.containsKey(stmt.getCatalogName())) {
            LOG.warn("Non catalog {} is found.", stmt.getCatalogName());
            return;
        }
        if (!nameToCatalogs.containsKey(stmt.getCatalogName())) {
            throw new DdlException("No catalog found with name: " + stmt.getCatalogName());
        }
        CatalogLog log = CatalogFactory.constructorCatalogLog(stmt);
        replayDropCatalog(log);
        Catalog.getCurrentCatalog().getEditLog().logDatasourceLog(OperationType.OP_DROP_DS, log);
    }

    /**
     * Modify the catalog name into a new one and write the meta log.
     */
    public void alterCatalogName(AlterCatalogNameStmt stmt) throws UserException {
        if (!nameToCatalogs.containsKey(stmt.getCatalogName())) {
            throw new DdlException("No catalog found with name: " + stmt.getCatalogName());
        }
        CatalogLog log = CatalogFactory.constructorCatalogLog(stmt);
        replayAlterCatalogName(log);
        Catalog.getCurrentCatalog().getEditLog().logDatasourceLog(OperationType.OP_ALTER_DS_NAME, log);
    }

    /**
     * Modify the catalog property and write the meta log.
     */
    public void alterCatalogProps(AlterCatalogPropertyStmt stmt) throws UserException {
        if (!nameToCatalogs.containsKey(stmt.getCatalogName())) {
            throw new DdlException("No catalog found with name: " + stmt.getCatalogName());
        }
        if (!nameToCatalogs.get(stmt.getCatalogName())
                .getType().equalsIgnoreCase(stmt.getNewProperties().get("type"))) {
            throw new DdlException("Can't modify the type of catalog property with name: " + stmt.getCatalogName());
        }
        CatalogLog log = CatalogFactory.constructorCatalogLog(stmt);
        replayAlterCatalogProps(log);
        Catalog.getCurrentCatalog().getEditLog().logDatasourceLog(OperationType.OP_ALTER_DS_PROPS, log);
    }

    /**
     * List all catalog or get the special catalog with a name.
     */
    public ShowResultSet showCatalogs(ShowCatalogStmt showStmt) throws AnalysisException {
        List<List<String>> rows = Lists.newArrayList();
        readLock();
        try {
            if (showStmt.getCatalogName() == null) {
                for (DataSourceIf ds : nameToCatalogs.values()) {
                    List<String> row = Lists.newArrayList();
                    row.add(ds.getName());
                    row.add(ds.getType());
                    rows.add(row);
                }
            } else {
                if (!nameToCatalogs.containsKey(showStmt.getCatalogName())) {
                    throw new AnalysisException("No catalog found with name: " + showStmt.getCatalogName());
                }
                DataSourceIf ds = nameToCatalogs.get(showStmt.getCatalogName());
                for (Map.Entry<String, String>  elem : ds.getProperties().entrySet()) {
                    List<String> row = Lists.newArrayList();
                    row.add(elem.getKey());
                    row.add(elem.getValue());
                    rows.add(row);
                }
            }
        } finally {
            readUnlock();
        }

        return new ShowResultSet(showStmt.getMetaData(), rows);
    }

    /**
     * Reply for create catalog event.
     */
    public void replayCreateCatalog(CatalogLog log) {
        writeLock();
        try {
            DataSourceIf ds = CatalogFactory.constructorFromLog(log);
            nameToCatalogs.put(ds.getName(), ds);
        } finally {
            writeUnlock();
        }
    }

    /**
     * Reply for drop catalog event.
     */
    public void replayDropCatalog(CatalogLog log) {
        writeLock();
        try {
            nameToCatalogs.remove(log.getCatalogName());
        } finally {
            writeUnlock();
        }
    }

    /**
     * Reply for alter catalog name event.
     */
    public void replayAlterCatalogName(CatalogLog log) {
        writeLock();
        try {
            DataSourceIf ds = nameToCatalogs.remove(log.getCatalogName());
            ds.modifyDatasourceName(log.getNewCatalogName());
            nameToCatalogs.put(ds.getName(), ds);
        } finally {
            writeUnlock();
        }
    }

    /**
     * Reply for alter catalog props event.
     */
    public void replayAlterCatalogProps(CatalogLog log) {
        writeLock();
        try {
            DataSourceIf ds = nameToCatalogs.remove(log.getCatalogName());
            ds.modifyDatasourceProps(log.getNewProps());
            nameToCatalogs.put(ds.getName(), ds);
        } finally {
            writeUnlock();
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
        Text.writeString(out, GsonUtils.GSON.toJson(this));
    }

    public static DataSourceMgr read(DataInput in) throws IOException {
        String json = Text.readString(in);
        return GsonUtils.GSON.fromJson(json, DataSourceMgr.class);
    }
}
