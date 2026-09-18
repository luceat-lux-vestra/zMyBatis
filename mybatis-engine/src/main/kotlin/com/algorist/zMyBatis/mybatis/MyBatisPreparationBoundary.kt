package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.MyBatisPreparationRequest
import com.algorist.zMyBatis.core.preparation.PreparationResult
import com.algorist.zMyBatis.core.preparation.PreparationSource
import com.algorist.zMyBatis.core.preparation.XmlMapperPreparationSource

/**
 * Source-kind dispatcher for the #64 preparation boundary.
 *
 * The Java implementation remains in [MyBatisPreparationEngine] while the bounded XML island is
 * isolated in [XmlMapperPreparationEngine]. Callers that are source-kind agnostic use this object
 * so adding XML support does not duplicate source dispatch outside :mybatis-engine.
 */
object MyBatisPreparationBoundary {
    fun prepare(request: MyBatisPreparationRequest): PreparationResult = when (request.source) {
        is PreparationSource.JavaAnnotation -> MyBatisPreparationEngine.prepare(request)
        is XmlMapperPreparationSource -> XmlMapperPreparationEngine.prepare(request)
    }
}
