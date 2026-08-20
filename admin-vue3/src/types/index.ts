export interface FinancialIncome {
  id: number;
  incomeOrderNo: string;
  cardKeyId: number;
  cardKey: string;
  userPhone: string;
  packageId: number;
  packageName: string;
  faceValue: number;
  amount: number;
  discountAmount: number;
  orderType: 'NORMAL_SALE' | 'DISCOUNT_SALE' | 'GIFT_FREE';
  paymentChannel: 'ALIPAY' | 'WECHAT_PAY' | 'BANK_TRANSFER' | 'OFFLINE';
  paymentTxnNo?: string;
  auditAdmin: string;
  activatedAt: string;
  auditRemark?: string;
}

export interface CompanyExpense {
  id: number;
  expenseOrderNo: string;
  category: 'TOKEN_PURCHASE' | 'SERVER_PROXY' | 'SMS_GATEWAY';
  tokenBatchId?: string;
  tokenCount: number;
  supplierName: string;
  unitCost: number;
  totalCost: number;
  invoiceUrl?: string;
  purchaser: string;
  purchasedAt: string;
}

export interface CardKeyItem {
  id: number;
  cardKey: string;
  packageId: number;
  status: 'UNUSED' | 'ACTIVATED' | 'VOID';
  generatedByAdmin: string;
  agentId?: number;
  activatedByPhone?: string;
  activatedAt?: string;
  activatedDeviceId?: string;
  createdAt: string;
}

export interface TokenPoolItem {
  id: number;
  tokenVal: string;
  accountAlias: string;
  healthStatus: 'HEALTHY' | 'BUSY' | 'FAULT_BLACK' | 'EXPIRED';
  dailyCallsCount: number;
  dailyMaxCapacity: number;
  riskScore: number;
    leaseClientPhone?: string;
    leasedAt?: string;
    lastFaultTime?: string;
    uuid: string;
    isDiscarded: number;
}

export interface FinanceSummary {
  totalIncome: number;
  normalSaleIncome: number;
  discountSaleIncome: number;
  giftValue: number;
  totalExpense: number;
  netProfit: number;
  profitMarginRate: number;
  totalCardsActivated: number;
  activeTokenCount: number;
}

export interface ClientUser {
  id: number;
  phone: string;
  status: 'ACTIVE' | 'TRIAL' | 'FROZEN';
  roleCode: string;
  invitationCode?: string;
  invitedByPhone?: string;
  currentPackageId?: number;
  currentPackageName?: string;
  remainingCalls: number;
  dailyCallsLimit?: number;
  maxAccounts?: number;
  deviceId?: string;
  expireTime?: string;
  createdAt?: string;
}

export interface PackagePlanLite {
  id: number;
  name: string;
  status: 'ACTIVE' | 'INACTIVE';
  durationHours: number;
  accountCount: number;
  callsPerAccount: number;
  listPrice: number;
  salePrice: number;
  description?: string;
}

export interface UserAssignmentDetail {
  userId: number;
  phone?: string;
  currentPackageName?: string;
  expireTime?: string;
  remainingCalls: number;
  totalAllocated: number;
  totalUsed: number;
  accounts: AssignmentItem[];
}

export interface AssignmentItem {
  slotIndex?: number;
  uuid?: string;
  accountAlias?: string;
  healthStatus?: 'HEALTHY' | 'BUSY' | 'FAULT_BLACK' | 'EXPIRED';
  allocatedCalls: number;
  usedCalls: number;
  remaining: number;
  status?: string;
  expireAt?: string;
}

export interface AdminAccount {
  id: number;
  username: string;
  displayName: string;
  roleCode: 'SUPER_ADMIN' | 'PARTNER';
  status: 'ACTIVE' | 'DISABLED';
  lastLoginAt?: string;
  createdAt?: string;
}

