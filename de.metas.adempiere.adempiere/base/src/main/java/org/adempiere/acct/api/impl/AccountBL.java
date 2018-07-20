package org.adempiere.acct.api.impl;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import java.math.BigDecimal;
import java.util.List;

import org.adempiere.acct.api.IAccountBL;
import org.adempiere.acct.api.IAccountDimension;
import org.adempiere.acct.api.IAccountDimensionValidator;
import org.adempiere.acct.api.IAcctSchemaDAO;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.compiere.model.I_AD_Org;
import org.compiere.model.I_C_AcctSchema;
import org.compiere.model.I_C_AcctSchema_Element;
import org.compiere.model.I_C_Activity;
import org.compiere.model.I_C_BPartner;
import org.compiere.model.I_C_Campaign;
import org.compiere.model.I_C_ElementValue;
import org.compiere.model.I_C_Location;
import org.compiere.model.I_C_Project;
import org.compiere.model.I_C_SalesRegion;
import org.compiere.model.I_C_SubAcct;
import org.compiere.model.I_C_ValidCombination;
import org.compiere.model.I_M_Product;
import org.compiere.model.X_C_AcctSchema_Element;
import org.compiere.model.X_C_ElementValue;
import org.slf4j.Logger;
import de.metas.logging.LogManager;

public class AccountBL implements IAccountBL
{
	private static final String SEGMENT_COMBINATION_NA = "_";
	private static final String SEGMENT_DESCRIPTION_NA = "_";

	private final transient Logger log = LogManager.getLogger(getClass());

	@Override
	public IAccountDimensionValidator createAccountDimensionValidator(final I_C_AcctSchema acctSchema)
	{
		return new AccountDimensionValidator(acctSchema);
	}

	@Override
	public void setValueDescription(final I_C_ValidCombination account)
	{
		final IAcctSchemaDAO acctSchemaDAO = Services.get(IAcctSchemaDAO.class);

		final StringBuilder combination = new StringBuilder();
		final StringBuilder description = new StringBuilder();
		boolean fullyQualified = true;

		final I_C_AcctSchema as = account.getC_AcctSchema();
		final String separator = as.getSeparator();

		//
		final List<I_C_AcctSchema_Element> elements = acctSchemaDAO.retrieveSchemaElements(as);
		for (final I_C_AcctSchema_Element element : elements)
		{
			// Skip those elements which are not displayed in editor (07546)
			if (!element.isDisplayInEditor())
			{
				continue;
			}

			String segmentCombination = SEGMENT_COMBINATION_NA;		// not defined
			String segmentDescription = SEGMENT_DESCRIPTION_NA;

			final String elementType = element.getElementType();
			Check.assumeNotNull(elementType, "elementType not null"); // shall not happen

			if (X_C_AcctSchema_Element.ELEMENTTYPE_Organization.equals(elementType))
			{
				if (account.getAD_Org_ID() > 0)
				{
					final I_AD_Org org = account.getAD_Org();
					segmentCombination = org.getValue();
					segmentDescription = org.getName();
				}
				else
				{
					segmentCombination = "*";
					segmentDescription = "*";
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_Account.equals(elementType))
			{
				if (account.getAccount_ID() > 0)
				{
					final I_C_ElementValue elementValue = account.getAccount();
					segmentCombination = elementValue.getValue();
					segmentDescription = elementValue.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Account");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_SubAccount.equals(elementType))
			{
				if (account.getC_SubAcct_ID() > 0)
				{
					final I_C_SubAcct sa = account.getC_SubAcct();
					segmentCombination = sa.getValue();
					segmentDescription = sa.getName();
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_Product.equals(elementType))
			{
				if (account.getM_Product_ID() > 0)
				{
					final I_M_Product product = account.getM_Product();
					segmentCombination = product.getValue();
					segmentDescription = product.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Product");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_BPartner.equals(elementType))
			{
				if (account.getC_BPartner_ID() > 0)
				{
					I_C_BPartner partner = account.getC_BPartner();
					segmentCombination = partner.getValue();
					segmentDescription = partner.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Business Partner");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_OrgTrx.equals(elementType))
			{
				if (account.getAD_OrgTrx_ID() > 0)
				{
					I_AD_Org org = account.getAD_OrgTrx();
					segmentCombination = org.getValue();
					segmentDescription = org.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Trx Org");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_LocationFrom.equals(elementType))
			{
				if (account.getC_LocFrom_ID() > 0)
				{
					final I_C_Location loc = account.getC_LocFrom();
					segmentCombination = loc.getPostal();
					segmentDescription = loc.getCity();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Location From");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_LocationTo.equals(elementType))
			{
				if (account.getC_LocTo_ID() > 0)
				{
					final I_C_Location loc = account.getC_LocTo();
					segmentCombination = loc.getPostal();
					segmentDescription = loc.getCity();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Location To");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_SalesRegion.equals(elementType))
			{
				if (account.getC_SalesRegion_ID() > 0)
				{
					final I_C_SalesRegion loc = account.getC_SalesRegion();
					segmentCombination = loc.getValue();
					segmentDescription = loc.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: SalesRegion");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_Project.equals(elementType))
			{
				if (account.getC_Project_ID() > 0)
				{
					final I_C_Project project = account.getC_Project();
					segmentCombination = project.getValue();
					segmentDescription = project.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Project");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_Campaign.equals(elementType))
			{
				if (account.getC_Campaign_ID() > 0)
				{
					final I_C_Campaign campaign = account.getC_Campaign();
					segmentCombination = campaign.getValue();
					segmentDescription = campaign.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Campaign");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_Activity.equals(elementType))
			{
				if (account.getC_Activity_ID() > 0)
				{
					final I_C_Activity act = account.getC_Activity();
					segmentCombination = act.getValue();
					segmentDescription = act.getName();
				}
				else if (element.isMandatory())
				{
					log.warn("Mandatory Element missing: Campaign");
					fullyQualified = false;
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_UserList1.equals(elementType))
			{
				if (account.getUser1_ID() > 0)
				{
					final I_C_ElementValue ev = account.getUser1();
					segmentCombination = ev.getValue();
					segmentDescription = ev.getName();
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_UserList2.equals(elementType))
			{
				if (account.getUser2_ID() > 0)
				{
					final I_C_ElementValue ev = account.getUser2();
					segmentCombination = ev.getValue();
					segmentDescription = ev.getName();
				}
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_UserElement1.equals(elementType))
			{
				// TODO: implement
				// if (acct.getUserElement1_ID() > 0)
				// {
				// }
			}
			else if (X_C_AcctSchema_Element.ELEMENTTYPE_UserElement2.equals(elementType))
			{
				// TODO: implement
				// if (acct.getUserElement2_ID() > 0)
				// {
				// }
			}

			//
			// Append segment combination and description
			if (combination.length() > 0)
			{
				combination.append(separator);
				description.append(separator);
			}
			combination.append(segmentCombination);
			description.append(segmentDescription);
		}

		//
		// Set Values
		account.setCombination(combination.toString());
		account.setDescription(description.toString());
		if (fullyQualified != account.isFullyQualified())
		{
			account.setIsFullyQualified(fullyQualified);
		}
	}	// setValueDescription

	@Override
	public void validate(final I_C_ValidCombination account)
	{
		Check.assumeNotNull(account, "account not null");

		// Validate Sub-Account
		if (account.getC_SubAcct_ID() > 0)
		{
			I_C_SubAcct sa = account.getC_SubAcct();
			if (sa.getC_ElementValue_ID() != account.getAccount_ID())
			{
				throw new AdempiereException("C_SubAcct.C_ElementValue_ID=" + sa.getC_ElementValue_ID() + "<>Account_ID=" + account.getAccount_ID());
			}
		}
	}

	@Override
	public IAccountDimension createAccountDimension(final I_C_ElementValue ev, final int acctSchemaId)
	{
		return AccountDimension.builder()
				.setAD_Client_ID(ev.getAD_Client_ID())
				.setC_ElementValue_ID(ev.getC_ElementValue_ID())
				.setC_AcctSchema_ID(acctSchemaId)
				.build();
	}

	@Override
	public IAccountDimension createAccountDimension(final I_C_ValidCombination account)
	{
		return AccountDimension.builder()
				.setAlias(account.getAlias())
				.setC_AcctSchema_ID(account.getC_AcctSchema_ID())
				.setAD_Client_ID(account.getAD_Client_ID())
				.setAD_Org_ID(account.getAD_Org_ID())
				.setC_ElementValue_ID(account.getAccount_ID())
				.setC_SubAcct_ID(account.getC_SubAcct_ID())
				.setM_Product_ID(account.getM_Product_ID())
				.setC_BPartner_ID(account.getC_BPartner_ID())
				.setAD_OrgTrx_ID(account.getAD_OrgTrx_ID())
				.setC_LocFrom_ID(account.getC_LocFrom_ID())
				.setC_LocTo_ID(account.getC_LocTo_ID())
				.setC_SalesRegion_ID(account.getC_SalesRegion_ID())
				.setC_Project_ID(account.getC_Project_ID())
				.setC_Campaign_ID(account.getC_Campaign_ID())
				.setC_Activity_ID(account.getC_Activity_ID())
				.setUser1_ID(account.getUser1_ID())
				.setUser2_ID(account.getUser2_ID())
				.setUserElement1_ID(account.getUserElement1_ID())
				.setUserElement2_ID(account.getUserElement2_ID())
				.build();
	}

	@Override
	public BigDecimal calculateBalance(final I_C_ElementValue account, final BigDecimal amtDr, final BigDecimal amtCr)
	{
		// NOTE: keep in sync with database function "acctBalance"

		//
		// Calculate initial balance as: DR - CR
		// (consider NULLs as ZERO)
		BigDecimal balance = amtDr == null ? BigDecimal.ZERO : amtDr;
		if (amtCr != null)
		{
			balance = balance.subtract(amtCr);
		}

		//
		// If there is no account, we can not adjust the balance based on AccountSign and AccountType
		if (account == null)
		{
			return balance;
		}

		//
		// If Natural Sign => detect the actual sign (Debit/Credit) based on AccountType
		String accountSign = account.getAccountSign();
		if (X_C_ElementValue.ACCOUNTSIGN_Natural.equals(accountSign))
		{
			final String accountType = account.getAccountType();
			if (X_C_ElementValue.ACCOUNTTYPE_Asset.equals(accountType)
					|| X_C_ElementValue.ACCOUNTTYPE_Expense.equals(accountType))
			{
				accountSign = X_C_ElementValue.ACCOUNTSIGN_Debit;
			}
			else
			{
				accountSign = X_C_ElementValue.ACCOUNTSIGN_Credit;
			}
		}

		//
		// If account sign is Credit => adjust the balance
		if (X_C_ElementValue.ACCOUNTSIGN_Credit.equals(accountSign))
		{
			balance = balance.negate(); // i.e. CR - DR
		}

		return balance;
	}
}