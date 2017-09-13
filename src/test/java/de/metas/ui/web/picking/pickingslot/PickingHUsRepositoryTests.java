package de.metas.ui.web.picking.pickingslot;

import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.save;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.adempiere.test.AdempiereTestHelper;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;

import de.metas.handlingunits.model.I_M_Picking_Candidate;
import de.metas.handlingunits.model.X_M_Picking_Candidate;
import de.metas.handlingunits.picking.IHUPickingSlotBL;
import de.metas.ui.web.handlingunits.HUEditorRow;
import de.metas.ui.web.handlingunits.HUEditorRowId;
import de.metas.ui.web.handlingunits.HUEditorRowType;
import de.metas.ui.web.handlingunits.HUEditorViewRepository;
import de.metas.ui.web.picking.pickingslot.PickingHuRowsRepository;
import de.metas.ui.web.picking.pickingslot.PickingSlotRepoQuery;
import de.metas.ui.web.picking.pickingslot.PickingHuRowsRepository.PickedHUEditorRow;
import de.metas.ui.web.window.datatypes.WindowId;
import lombok.NonNull;
import mockit.Expectations;
import mockit.Mocked;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@RunWith(Theories.class)
public class PickingHUsRepositoryTests
{
	private static final int M_HU_ID = 223;

	private static final int M_PICKINGSLOT_ID = 323;

	private static final int M_SHIPMENT_SCHEDULE_ID = 123;

	@Mocked
	private HUEditorViewRepository huEditorViewRepository;

	/**
	 * Needed to test {@link PickingHuRowsRepository#retrieveSourceHUs(List)}.
	 */
	@Mocked
	private IHUPickingSlotBL huPickingSlotBL;

	@DataPoints
	public static String[] pickingCandidateStates()
	{
		return new String[] {
				X_M_Picking_Candidate.STATUS_IP,
				X_M_Picking_Candidate.STATUS_PR,
				X_M_Picking_Candidate.STATUS_CL
		};
	}

	@Before
	public void init()
	{
		AdempiereTestHelper.get().init();
	}

	/**
	 * Tests {@link PickingHuRowsRepository#retrievePickedHUsIndexedByPickingSlotId(PickingSlotRepoQuery)} with a simple mocked {@link HUEditorViewRepository} that returns one HURow.
	 * 
	 * @param pickingCandidateStatus this value is given to the {@link I_M_Picking_Candidate} we test with, and we verify that this value is correctly translated it to the resulting {@link PickingSlotHUEditorRow#isProcessed()}.
	 */
	@Theory
	public void test_retrieveHUsIndexedByPickingSlotId(@NonNull final String pickingCandidateStatus)
	{
		final I_M_Picking_Candidate pickingCandidate = newInstance(I_M_Picking_Candidate.class);
		pickingCandidate.setM_ShipmentSchedule_ID(M_SHIPMENT_SCHEDULE_ID);
		pickingCandidate.setM_HU_ID(M_HU_ID);
		pickingCandidate.setM_PickingSlot_ID(M_PICKINGSLOT_ID);
		pickingCandidate.setStatus(pickingCandidateStatus);
		save(pickingCandidate);

		final HUEditorRow huEditorRow = HUEditorRow
				.builder(WindowId.of(423))
				.setRowId(HUEditorRowId.ofTopLevelHU(M_HU_ID))
				.setType(HUEditorRowType.LU)
				.setTopLevel(true)
				.build();

		if (!X_M_Picking_Candidate.STATUS_CL.equals(pickingCandidateStatus))
		{
			// @formatter:off
			new Expectations() {{ huEditorViewRepository.retrieveHUEditorRows(ImmutableSet.of(M_HU_ID)); result = huEditorRow; }};
			// @formatter:on
		}

		final PickingHuRowsRepository pickingHUsRepository = new PickingHuRowsRepository(huEditorViewRepository);
		final ListMultimap<Integer, PickedHUEditorRow> result = pickingHUsRepository.retrievePickedHUsIndexedByPickingSlotId(PickingSlotRepoQuery.of(M_SHIPMENT_SCHEDULE_ID));

		if (X_M_Picking_Candidate.STATUS_CL.equals(pickingCandidateStatus))
		{
			// if 'pickingCandidate' is "closed", then nothing shall be returned
			assertThat(result.size(), is(0));
		}
		else
		{
			assertThat(result.size(), is(1));
			assertThat(result.get(M_PICKINGSLOT_ID).size(), is(1));

			final boolean expectedProcessed = !X_M_Picking_Candidate.STATUS_IP.equals(pickingCandidateStatus);
			assertThat(result.get(M_PICKINGSLOT_ID).get(0), is(new PickedHUEditorRow(huEditorRow, expectedProcessed)));
		}
	}

	@Test
	public void test_retrieveSourceHUs_empty_shipmentScheduleIds()
	{
		final PickingHuRowsRepository pickingHUsRepository = new PickingHuRowsRepository(huEditorViewRepository);
		final List<HUEditorRow> sourceHUs = pickingHUsRepository.retrieveSourceHUs(ImmutableList.of());
		assertThat(sourceHUs).isEmpty();
	}
}
