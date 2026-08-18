export interface PackageTier {
  id: number;
  name: string;
  price: number;
  durationDays: number;
  accountCountX: number;
  callsPerAccountY: number;
  totalCalls: number;
  description: string;
  isTrial?: boolean;
}

export interface UserAccount {
  id: number;
  phone: string;
  registeredAt: string;
  status: 'TRIAL' | 'ACTIVE' | 'EXPIRED' | 'BLOCKED';
  expireTime: string;
  totalSpentAmount: number;
  totalCardsCount: number;
  totalCallsLimit: number;
  usedCalls: number;
  remainingCalls: number;
}

// 独立的卡密凭证表实体
export interface CardKeyEntity {
  id: number;
  cardKey: string;
  packageId: number;
  packageName: string;
  faceValue: number;
  status: 'UNUSED' | 'ACTIVATED' | 'VOIDED';
  generatedByAdmin: string;
  generatedAt: string;
  boundUserPhone?: string;
  activatedAt?: string;
}

// 独立的财务收入流水表实体
export interface FinancialIncomeEntity {
  id: number;
  incomeOrderNo: string;
  cardKeyId: number;
  cardKey: string;
  userPhone: string;
  packageId: number;
  packageName: string;
  faceValue?: number;
  amount: number;
  discountAmount?: number;
  orderType?: 'NORMAL_SALE' | 'DISCOUNT_SALE' | 'GIFT_FREE';
  paymentChannel: 'BANK_TRANSFER' | 'ALIPAY' | 'WECHAT_PAY';
  paymentTxnNo: string;
  auditAdmin: string;
  activatedAt: string;
  auditRemark: string;
}

// 独立的公司资产采购与支出表实体
export interface CompanyExpenseEntity {
  id: number;
  expenseNo: string;
  expenseType: 'ASSET_TOKEN_PURCHASE' | 'TOKEN_SUPPLEMENT' | 'SERVER_BANDWIDTH' | 'OTHER';
  amount: number;
  tokenCountAdded: number;
  unitPrice: number;
  supplierChannel: string;
  recordedByAdmin: string;
  expenseDate: string;
  remark: string;
}

// 财务多维报表汇总类型
export interface FinancialAuditReport {
  period: string; // 2026年 / 2026-Q3 / 2026-08
  totalCardsIssued: number;
  totalCardsActivated: number;
  totalIncomeAmount: number;
  totalExpenseAmount: number;
  netProfit: number;
}
