package de.metas.order.impl;

import java.math.BigDecimal;
import java.sql.Timestamp;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.Services;
import org.compiere.model.I_C_BPartner_Location;
import org.compiere.model.X_C_OrderLine;

import de.metas.interfaces.I_C_OrderLine;
import de.metas.lang.Percent;
import de.metas.order.IOrderBL;
import de.metas.order.OrderLinePriceUpdateRequest;
import de.metas.order.OrderLinePriceUpdateRequest.ResultUOM;
import de.metas.order.PriceAndDiscount;
import de.metas.pricing.IEditablePricingContext;
import de.metas.pricing.IPricingContext;
import de.metas.pricing.IPricingResult;
import de.metas.pricing.PricingConditionsResult;
import de.metas.pricing.conditions.PricingConditionsBreakId;
import de.metas.pricing.exceptions.ProductNotOnPriceListException;
import de.metas.pricing.limit.PriceLimitRuleContext;
import de.metas.pricing.limit.PriceLimitRuleResult;
import de.metas.pricing.service.IPricingBL;
import de.metas.quantity.Quantity;
import lombok.Builder;
import lombok.NonNull;

/*
 * #%L
 * de.metas.swat.base
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

class OrderLinePriceCalculator
{
	private final IPricingBL pricingBL = Services.get(IPricingBL.class);
	private final IOrderBL orderBL = Services.get(IOrderBL.class);

	private final OrderLineBL orderLineBL;
	private final OrderLinePriceUpdateRequest request;

	@Builder
	private OrderLinePriceCalculator(
			@NonNull final OrderLinePriceUpdateRequest request,
			@NonNull final OrderLineBL orderLineBL)
	{
		this.orderLineBL = orderLineBL;
		this.request = request;

	}

	public void updateOrderLine()
	{
		final I_C_OrderLine orderLine = request.getOrderLine();

		// Product was not set yet. There is no point to calculate the prices
		if (orderLine.getM_Product_ID() <= 0)
		{
			return;
		}

		//
		// Calculate Pricing Result
		final IEditablePricingContext pricingCtx = createPricingContext();
		final IPricingResult pricingResult = pricingBL.calculatePrice(pricingCtx);
		if (!pricingResult.isCalculated())
		{
			throw new ProductNotOnPriceListException(pricingCtx, orderLine.getLine())
					.setParameter("pricingResult", pricingResult);
		}

		PriceAndDiscount priceAndDiscount = extractPriceAndDiscount(pricingResult, pricingCtx.isSOTrx());

		//
		// Apply price limit restrictions
		if (isApplyPriceLimitRestrictions(pricingResult))
		{
			final PriceLimitRuleResult priceLimitResult = pricingBL.computePriceLimit(PriceLimitRuleContext.builder()
					.pricingContext(pricingCtx)
					.priceLimit(priceAndDiscount.getPriceLimit())
					.priceActual(priceAndDiscount.getPriceActual())
					.paymentTermId(orderLineBL.getC_PaymentTerm_ID(orderLine))
					.build());

			priceAndDiscount = priceAndDiscount.enforcePriceLimit(priceLimitResult);
		}

		//
		// Prices
		priceAndDiscount.applyTo(orderLine);
		orderLine.setPriceList(pricingResult.getPriceList());
		orderLine.setPriceStd(pricingResult.getPriceStd());
		orderLine.setPrice_UOM_ID(pricingResult.getPrice_UOM_ID()); // 07090: when setting a priceActual, we also need to specify a PriceUOM

		//
		// C_Currency_ID, M_PriceList_Version_ID
		orderLine.setC_Currency_ID(pricingResult.getC_Currency_ID());
		orderLine.setM_PriceList_Version_ID(pricingResult.getM_PriceList_Version_ID());

		orderLine.setIsPriceEditable(pricingResult.isPriceEditable());
		orderLine.setIsDiscountEditable(pricingResult.isDiscountEditable());
		orderLine.setEnforcePriceLimit(pricingResult.isEnforcePriceLimit());

		updateOrderLineFromPricingConditionsResult(orderLine, pricingResult.getPricingConditions());

		//
		//
		if (request.isUpdateLineNetAmt())
		{
			if (request.getQtyOverride() != null)
			{
				orderLineBL.updateLineNetAmt(orderLine, request.getQtyOverride());
			}
			else
			{
				orderLineBL.updateLineNetAmt(orderLine, orderLineBL.getQtyEntered(orderLine));
			}
		}
	}

	private void updateOrderLineFromPricingConditionsResult(final I_C_OrderLine orderLine, final PricingConditionsResult pricingConditionsResult)
	{
		final PricingConditionsBreakId pricingConditionsBreakId;
		final int basePricingSystemId;
		final int paymentTermId;
		if (pricingConditionsResult != null)
		{
			pricingConditionsBreakId = pricingConditionsResult.getPricingConditionsBreakId();
			basePricingSystemId = pricingConditionsResult.getBasePricingSystemId();
			paymentTermId = pricingConditionsResult.getPaymentTermId();
		}
		else
		{
			pricingConditionsBreakId = null;
			basePricingSystemId = -1;
			paymentTermId = -1;
		}

		orderLine.setM_DiscountSchemaBreak_ID(pricingConditionsBreakId != null ? pricingConditionsBreakId.getDiscountSchemaBreakId() : 0);
		orderLine.setBase_PricingSystem_ID(basePricingSystemId);
		orderLine.setC_PaymentTerm_Override_ID(paymentTermId);
	}

	public IEditablePricingContext createPricingContext()
	{
		final I_C_OrderLine orderLine = request.getOrderLine();
		final org.compiere.model.I_C_Order order = orderLine.getC_Order();

		final boolean isSOTrx = order.isSOTrx();
		final int productId = orderLine.getM_Product_ID();
		int bpartnerId = orderLine.getC_BPartner_ID();
		if (bpartnerId <= 0)
		{
			bpartnerId = order.getC_BPartner_ID();
		}

		final Timestamp date = OrderLineBL.getPriceDate(orderLine, order);

		final BigDecimal qtyInPriceUOM;
		if (request.getQtyOverride() != null)
		{
			final Quantity qtyOverride = request.getQtyOverride();
			qtyInPriceUOM = orderLineBL.convertToPriceUOM(qtyOverride, orderLine).getQty();
		}
		else
		{
			qtyInPriceUOM = orderLineBL.convertQtyEnteredToPriceUOM(orderLine);
		}

		final IEditablePricingContext pricingCtx = pricingBL.createInitialContext(
				productId,
				bpartnerId,
				orderLine.getPrice_UOM_ID(),  // task 06942
				qtyInPriceUOM,
				isSOTrx);
		pricingCtx.setPriceDate(date);

		// 03152: setting the 'ol' to allow the subscription system to compute the right price
		pricingCtx.setReferencedObject(orderLine);

		//
		// Pricing System / List
		final int pricingSystemId = request.getPricingSystemIdOverride() > 0 ? request.getPricingSystemIdOverride() : pricingCtx.getM_PricingSystem_ID();
		final int priceListId = request.getPriceListIdOverride() > 0 ? request.getPriceListIdOverride() : orderBL.retrievePriceListId(order, pricingSystemId);
		pricingCtx.setM_PricingSystem_ID(pricingSystemId);
		pricingCtx.setM_PriceList_ID(priceListId);
		pricingCtx.setM_PriceList_Version_ID(-1);

		final int countryId = getCountryIdOrZero(orderLine);
		pricingCtx.setC_Country_ID(countryId);

		pricingCtx.setConvertPriceToContextUOM(isConvertPriceToContextUOM(request, pricingCtx.isConvertPriceToContextUOM()));

		//
		// Don't calculate the discount in case we are dealing with a percentage discount compensation group line (task 3149)
		if (orderLine.isGroupCompensationLine()
				&& X_C_OrderLine.GROUPCOMPENSATIONTYPE_Discount.equals(orderLine.getGroupCompensationType())
				&& X_C_OrderLine.GROUPCOMPENSATIONAMTTYPE_Percent.equals(orderLine.getGroupCompensationAmtType()))
		{
			pricingCtx.setDisallowDiscount(true);
		}
		
		pricingCtx.setForcePricingConditionsBreak(request.getPricingConditionsBreakOverride());

		return pricingCtx;
	}

	private static int getCountryIdOrZero(@NonNull final org.compiere.model.I_C_OrderLine orderLine)
	{
		if (orderLine.getC_BPartner_Location_ID() <= 0)
		{
			return 0;
		}

		final I_C_BPartner_Location bPartnerLocation = orderLine.getC_BPartner_Location();
		if (bPartnerLocation.getC_Location_ID() <= 0)
		{
			return 0;
		}

		final int countryId = bPartnerLocation.getC_Location().getC_Country_ID();
		return countryId;
	}

	private static boolean isConvertPriceToContextUOM(final OrderLinePriceUpdateRequest request, final boolean defaultValue)
	{
		final ResultUOM resultUOM = request.getResultUOM();
		if (resultUOM == null)
		{
			return defaultValue;
		}
		else if (resultUOM == ResultUOM.PRICE_UOM)
		{
			return false;
		}
		else if (resultUOM == ResultUOM.PRICE_UOM_IF_ORDERLINE_IS_NEW)
		{
			final boolean convertToPriceUOM = InterfaceWrapperHelper.isNew(request.getOrderLine());
			return !convertToPriceUOM;
		}
		else if (resultUOM == ResultUOM.CONTEXT_UOM)
		{
			return true;
		}
		else
		{
			throw new AdempiereException("ResultPriceUOM not supported: " + resultUOM);
		}
	}

	private boolean isApplyPriceLimitRestrictions(final IPricingResult pricingResult)
	{
		return request.isApplyPriceLimitRestrictions()
				&& request.getOrderLine().getC_Order().isSOTrx() // we enforce price limit only for sales orders
				&& pricingResult.isEnforcePriceLimit();
	}

	private PriceAndDiscount extractPriceAndDiscount(final IPricingResult pricingResult, final boolean isSOTrx)
	{
		return PriceAndDiscount.builder()
				.precision(pricingResult.getPrecision())
				.priceEntered(extractPriceEntered(pricingResult))
				.priceLimit(pricingResult.getPriceLimit())
				.discount(extractDiscount(pricingResult, isSOTrx))
				.build()
				.updatePriceActual();
	}

	private BigDecimal extractPriceEntered(final IPricingResult pricingResult)
	{
		if (isAllowChangingPriceEntered())
		{
			return pricingResult.getPriceStd();
		}
		else
		{
			final I_C_OrderLine orderLine = request.getOrderLine();
			return orderLine.getPriceEntered();
		}
	}

	private boolean isAllowChangingPriceEntered()
	{
		final I_C_OrderLine orderLine = request.getOrderLine();
		if (orderLine.isManualPrice())
		{
			return false;
		}
		if (request.isUpdatePriceEnteredAndDiscountOnlyIfNotAlreadySet() && orderLine.getPriceEntered().signum() != 0) // task 06727
		{
			return false;
		}

		return true;
	}

	private Percent extractDiscount(final IPricingResult pricingResult, final boolean isSOTrx)
	{
		if (isAllowChangingDiscount(isSOTrx))
		{
			return pricingResult.getDiscount();
		}
		else
		{
			final I_C_OrderLine orderLine = request.getOrderLine();
			return Percent.of(orderLine.getDiscount());
		}
	}

	private boolean isAllowChangingDiscount(final boolean isSOTrx)
	{
		if (!isSOTrx)
		{
			return true;
		}

		final I_C_OrderLine orderLine = request.getOrderLine();
		if (orderLine.isManualDiscount())
		{
			return false;
		}

		if (request.isUpdatePriceEnteredAndDiscountOnlyIfNotAlreadySet() && orderLine.getDiscount().signum() != 0) // task 06727
		{
			return false;
		}

		return true;
	}

	public int computeTaxCategoryId()
	{
		final IPricingContext pricingCtx = createPricingContext();

		final IPricingResult pricingResult = pricingBL.calculatePrice(pricingCtx);
		if (!pricingResult.isCalculated())
		{
			final I_C_OrderLine orderLine = request.getOrderLine();
			throw new ProductNotOnPriceListException(pricingCtx, orderLine.getLine());
		}

		return pricingResult.getC_TaxCategory_ID();
	}

	public IPricingResult computePrices()
	{
		final IEditablePricingContext pricingCtx = createPricingContext();
		return pricingBL.calculatePrice(pricingCtx);
	}

	public PriceLimitRuleResult computePriceLimit()
	{
		final I_C_OrderLine orderLine = request.getOrderLine();

		return pricingBL.computePriceLimit(PriceLimitRuleContext.builder()
				.pricingContext(createPricingContext())
				.priceLimit(orderLine.getPriceLimit())
				.priceActual(orderLine.getPriceActual())
				.paymentTermId(orderLineBL.getC_PaymentTerm_ID(orderLine))
				.build());
	}
}