package com.algorist.zMyBatis.mybatis

import com.algorist.zMyBatis.core.preparation.PreparationFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyBatisParameterMappingCapabilityAdmissionTest {
    @Test
    fun customTypeHandlerIsRejectedAsApplicationRuntimeCapability() {
        val failure = MyBatisParameterMappingCapabilityAdmission.failureForSourceText(
            "select #{id,typeHandler=fixture.CustomHandler}",
        )

        requireNotNull(failure)
        assertEquals(PreparationFailureKind.UNSUPPORTED_TYPE_HANDLER, failure.kind)
        assertEquals("mybatis-custom-type-handler-unsupported", failure.code)
    }

    @Test
    fun stockTypeHandlerAndExplicitJavaTypeRemainStockOwned() {
        assertNull(
            MyBatisParameterMappingCapabilityAdmission.failureForSourceText(
                "select #{id,typeHandler=org.apache.ibatis.type.LongTypeHandler,javaType=java.lang.Long}",
            ),
        )
        assertNull(
            MyBatisParameterMappingCapabilityAdmission.failureForSourceText(
                "select #{id,javaType=java.lang.Long}",
            ),
        )
    }

    @Test
    fun escapedBoundTokenDoesNotAcquireMappingCapabilityAuthority() {
        assertNull(
            MyBatisParameterMappingCapabilityAdmission.failureForSourceText(
                "select \\#{id,typeHandler=fixture.CustomHandler}",
            ),
        )
    }
}
