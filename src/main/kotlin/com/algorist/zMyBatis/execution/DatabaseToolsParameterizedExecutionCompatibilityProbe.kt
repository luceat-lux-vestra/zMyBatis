package com.algorist.zMyBatis.execution

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.connection.statements.SmartStatementFactoryService
import com.intellij.database.dataSource.connection.statements.StatementParameters
import com.intellij.database.datagrid.DataRequest

/**
 * Temporary #251 compatibility probe. Removed before merge.
 *
 * Direct references are intentional: Build/Plugin Verifier must judge whether the candidate
 * Database Tools 2026.2 surface is actually consumable by a third-party plugin without reflection.
 */
internal object DatabaseToolsParameterizedExecutionCompatibilityProbe {
    fun parameterizedFactory(
        owner: DataRequest.OwnerEx,
        connection: DatabaseConnectionCore,
        service: SmartStatementFactoryService,
        parameters: StatementParameters,
    ): Any {
        owner.hashCode()
        parameters.hashCode()
        return service.poweredBy(connection).parameterized()
    }
}
