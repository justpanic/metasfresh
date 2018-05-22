/**
 *
 */
package de.metas.pricing.conditions.service.impl;

import java.math.BigDecimal;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Check;
import org.adempiere.util.Services;

import de.metas.lang.Percent;
import de.metas.pricing.IEditablePricingContext;
import de.metas.pricing.IPricingContext;
import de.metas.pricing.IPricingResult;
import de.metas.pricing.conditions.PriceOverride;
import de.metas.pricing.conditions.PriceOverrideType;
import de.metas.pricing.conditions.PricingConditions;
import de.metas.pricing.conditions.PricingConditionsBreak;
import de.metas.pricing.conditions.PricingConditionsDiscountType;
import de.metas.pricing.conditions.service.CalculatePricingConditionsRequest;
import de.metas.pricing.conditions.service.CalculatePricingConditionsResult;
import de.metas.pricing.conditions.service.CalculatePricingConditionsResult.CalculatePricingConditionsResultBuilder;
import de.metas.pricing.conditions.service.IPricingConditionsRepository;
import de.metas.pricing.service.IPricingBL;
import lombok.NonNull;

/*
 * #%L
 * de.metas.business
 * %%
 * Copyright (C) 2018 metas GmbH
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

/**
 * @author metas-dev <dev@metasfresh.com>
 *
 */
/* package */ class CalculatePricingConditionsCommand
{
	private final IPricingBL pricingBL = Services.get(IPricingBL.class);
	private final IPricingConditionsRepository pricingConditionsRepo = Services.get(IPricingConditionsRepository.class);

	final CalculatePricingConditionsRequest request;

	public CalculatePricingConditionsCommand(@NonNull final CalculatePricingConditionsRequest request)
	{
		this.request = request;
	}

	public CalculatePricingConditionsResult calculate()
	{
		final PricingConditions pricingConditions = pricingConditionsRepo.getPricingConditionsById(request.getPricingConditionsId());

		final PricingConditionsDiscountType discountType = pricingConditions.getDiscountType();
		if (discountType == PricingConditionsDiscountType.FLAT_PERCENT)
		{
			return computeFlatDiscount(pricingConditions);
		}
		else if (discountType == PricingConditionsDiscountType.FORMULA
				|| discountType == PricingConditionsDiscountType.PRICE_LIST)
		{
			return CalculatePricingConditionsResult.ZERO;
		}
		else if (discountType == PricingConditionsDiscountType.BREAKS)
		{
			return computeBreaksDiscount(pricingConditions);
		}
		else
		{
			throw new AdempiereException("@NotSupported@ @DiscountType@: " + discountType);
		}
	}

	private CalculatePricingConditionsResult computeFlatDiscount(final PricingConditions pricingConditions)
	{
		if (pricingConditions.isBpartnerFlatDiscount())
		{
			return CalculatePricingConditionsResult.discount(request.getBpartnerFlatDiscount());
		}
		else
		{
			return CalculatePricingConditionsResult.discount(pricingConditions.getFlatDiscount());
		}
	}

	private CalculatePricingConditionsResult computeBreaksDiscount(final PricingConditions pricingConditions)
	{
		final PricingConditionsBreak breakApplied = findMatchingPricingConditionBreak(pricingConditions);
		if (breakApplied == null)
		{
			return CalculatePricingConditionsResult.ZERO;
		}

		final CalculatePricingConditionsResultBuilder result = CalculatePricingConditionsResult.builder()
				.pricingConditionsBreakId(breakApplied.getId())
				.paymentTermId(breakApplied.getPaymentTermId());

		computePriceForPricingConditionsBreak(result, breakApplied.getPriceOverride());
		computeDiscountForPricingConditionsBreak(result, breakApplied);

		return result.build();
	}

	private void computePriceForPricingConditionsBreak(final CalculatePricingConditionsResultBuilder result, final PriceOverride priceOverride)
	{
		final PriceOverrideType priceOverrideType = priceOverride.getType();
		if (priceOverrideType == PriceOverrideType.NONE)
		{
			// nothing
		}
		else if (priceOverrideType == PriceOverrideType.BASE_PRICING_SYSTEM)
		{
			final int basePricingSystemId = priceOverride.getBasePricingSystemId();

			final IPricingResult productPrices = computePricesForBasePricingSystem(basePricingSystemId);
			final BigDecimal priceStd = productPrices.getPriceStd();
			final BigDecimal priceList = productPrices.getPriceList();
			final BigDecimal priceLimit = productPrices.getPriceLimit();

			final BigDecimal priceStdAddAmt = priceOverride.getBasePriceAddAmt();

			result.basePricingSystemId(basePricingSystemId);
			result.priceListOverride(priceList);
			result.priceLimitOverride(priceLimit);
			result.priceStdOverride(priceStd.add(priceStdAddAmt));
		}
		else if (priceOverrideType == PriceOverrideType.FIXED_PRICE)
		{
			result.priceStdOverride(priceOverride.getFixedPrice());
		}
		else
		{
			throw new AdempiereException("Unknow price override type: " + priceOverrideType)
					.setParameter("priceOverride", priceOverride);
		}
	}

	private IPricingResult computePricesForBasePricingSystem(final int basePricingSystemId)
	{
		final IPricingContext pricingCtx = request.getPricingCtx();
		Check.assumeNotNull(pricingCtx, "pricingCtx shall not be null for {}", request);

		final IPricingContext basePricingSystemPricingCtx = createBasePricingSystemPricingCtx(pricingCtx, basePricingSystemId);
		final IPricingResult pricingResult = pricingBL.calculatePrice(basePricingSystemPricingCtx);

		return pricingResult;
	}

	private static IPricingContext createBasePricingSystemPricingCtx(final IPricingContext pricingCtx, final int basePricingSystemId)
	{
		Check.assumeGreaterThanZero(basePricingSystemId, "basePricingSystemId");

		final IEditablePricingContext newPricingCtx = pricingCtx.copy();
		newPricingCtx.setM_PricingSystem_ID(basePricingSystemId);
		newPricingCtx.setM_PriceList_ID(-1); // will be recomputed
		newPricingCtx.setM_PriceList_Version_ID(-1); // will be recomputed
		newPricingCtx.setSkipCheckingPriceListSOTrxFlag(true);
		newPricingCtx.setDisallowDiscount(true);
		newPricingCtx.setFailIfNotCalculated(true);

		return newPricingCtx;
	}

	private void computeDiscountForPricingConditionsBreak(final CalculatePricingConditionsResultBuilder result, final PricingConditionsBreak pricingConditionsBreak)
	{
		final Percent discount;
		if (pricingConditionsBreak.isBpartnerFlatDiscount())
		{
			discount = request.getBpartnerFlatDiscount();
		}
		else
		{
			discount = pricingConditionsBreak.getDiscount();
		}

		result.discount(discount);
	}

	private PricingConditionsBreak findMatchingPricingConditionBreak(final PricingConditions pricingConditions)
	{
		if (request.getForcePricingConditionsBreak() != null)
		{
			return request.getForcePricingConditionsBreak();
		}
		else
		{
			return pricingConditions.pickApplyingBreak(request.getPricingConditionsBreakQuery());
		}
	}
}