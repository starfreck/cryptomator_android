package org.cryptomator.presentation.presenter

import android.app.Activity
import android.content.Intent
import org.cryptomator.domain.usecases.cloud.GetCloudsUseCase
import org.cryptomator.presentation.exception.ExceptionHandlers
import org.cryptomator.presentation.model.CloudTypeModel
import org.cryptomator.presentation.model.mappers.CloudModelMapper
import org.cryptomator.presentation.ui.activity.view.ChooseCloudServiceView
import org.cryptomator.presentation.workflow.AddExistingVaultWorkflow
import org.cryptomator.presentation.workflow.CreateNewVaultWorkflow
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ChooseCloudServicePresenterTest {

	private val view = mock<ChooseCloudServiceView>()
	private val activity = mock<Activity>()
	private val intent = mock<Intent>()
	private lateinit var inTest: ChooseCloudServicePresenter

	@BeforeEach
	fun setup() {
		whenever(view.activity()).thenReturn(activity)
		whenever(activity.intent).thenReturn(intent)

		inTest = ChooseCloudServicePresenter(
			mock<GetCloudsUseCase>(),
			mock<CloudModelMapper>(),
			mock<AddExistingVaultWorkflow>(),
			mock<CreateNewVaultWorkflow>(),
			mock<ExceptionHandlers>()
		)
		inTest.view = view
	}

	@Test
	fun testMegaIsAtTheEndOfChooseCloudServiceList() {
		inTest.resumed()

		val captor = argumentCaptor<List<CloudTypeModel>>()
		verify(view).render(captor.capture())

		val models = captor.firstValue
		assertThat(models.last(), `is`(CloudTypeModel.MEGA))
	}
}
