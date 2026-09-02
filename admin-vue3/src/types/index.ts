export interface FinancialIncome {
  id: number;
  bizId: number;
  incomeOrderNo: string;
  cardKeyId: number;
  cardKey: string;
  userPhone: string;
  packageId: number;
  packageName: string;
  faceValue: number;
  amount: number;
  discountAmount: number;
  orderType: 'NORMAL_SALE' | 'DISCOUNT_SALE' | 'GIFT_FREE' | 'RENEWAL';
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
  bizId: number;
  cardKey: string;
  packageId: number;
  status: 'UNUSED' | 'ASSIGNED' | 'ACTIVATED' | 'VOID';
  generatedByAdmin: string;
  agentId?: number;
  activatedByPhone?: string;
  activatedAt?: string;
  activatedDeviceId?: string;
  createdAt: string;
}

export interface TokenPoolItem {
  id: number;
  bizId: number;
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
  bizId: number;
  appId: number;
  businessName: string;
  businessDescription?: string;
  accountSource?: 'SELF_REGISTER' | 'ADMIN_CREATED';
  mustChangePassword?: boolean;
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
  /** 最近一次客户端登录成功时间，由 pdk_login_log 聚合回填，未迁移或从未登录时为空。 */
  lastLoginAt?: string;
  /** 最近一次客户端登录成功 IP。 */
  lastLoginIp?: string;
}

export interface LoginLog {
  id: number;
  bizId?: number;
  actorType: 'CLIENT' | 'ADMIN';
  actorId?: number;
  actorAccount: string;
  eventType: 'LOGIN' | 'LOGOUT' | 'PASSWORD_RESET' | 'FORCE_CHANGE' | 'DEVICE_UNBIND';
  result: 'SUCCESS' | 'FAIL';
  failReason?: string;
  ipAddress?: string;
  deviceId?: string;
  userAgent?: string;
  createdAt?: string;
}

export interface AdminAuditLog {
  id: number;
  bizId?: number;
  adminName: string;
  adminRole?: string;
  actionType: string;
  targetType?: string;
  targetId?: string;
  beforeState?: string;
  afterState?: string;
  reason?: string;
  ipAddress?: string;
  createdAt?: string;
}

export interface PackagePlanLite {
  id: number;
  bizId: number;
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
  bizId?: number;
  username: string;
  displayName: string;
  roleCode: 'SUPER_ADMIN' | 'PARTNER';
  status: 'ACTIVE' | 'DISABLED';
  lastLoginAt?: string;
  createdAt?: string;
}

export interface BusinessRuntime {
  bizId: number;
  appId: number;
  bizCode: string;
  businessName: string;
  businessDescription?: string;
  registrationMode: 'SELF_SERVICE' | 'ADMIN_ONLY';
  authorizationMode: 'USER_SUBSCRIPTION' | 'DEVICE_LICENSE';
  trialEnabled: boolean;
  trialDurationHours: number;
  trialAccountCount: number;
  trialCallsPerAccount: number;
  forceInitialPasswordChange: boolean;
  configuredStatus: 'ACTIVE' | 'DISABLED';
  deploymentEnabled: boolean;
  handlerRegistered: boolean;
    handlerHealth: 'UP' | 'DOWN';
    supportedActions: string[];
  effectiveStatus: string;
  unavailableReason?: string;
  userCount?: number;
  packageCount?: number;
  resourceCount?: number;
  availableResourceCount?: number;
}

export interface DeviceLicenseItem {
  licenseId: number;
  bizId: number;
  userId: number;
  cardKeyId: number;
  cardKeyMasked: string;
  userDeviceId?: number;
  deviceId?: string;
  deviceName?: string;
  status: 'UNBOUND' | 'ACTIVE' | 'EXPIRED' | 'SUSPENDED' | 'REVOKED';
  packageId: number;
  packageName: string;
  activatedAt?: string;
  effectiveAt?: string;
  expireAt?: string;
  remainingCalls: number;
  totalCalls: number;
  lastLoginAt?: string;
  serverTime: string;
}

export interface LicenseExportResult {
  fileName: string;
  csv: string;
  recordCount: number;
}

export interface ClientRelease {
  id: number; bizId: number; version: string; channel: 'STABLE'|'BETA'; status: 'DRAFT'|'READY'|'PUBLISHED'|'SUSPENDED'|'ARCHIVED';
  minimumProtocolVersion: number; minimumUpdaterVersion: string; releaseNotes?: string; rolloutPercentage: number;
  publishedAt?: string; createdAt?: string;
}
export interface ClientArtifact {
  id: number; releaseId: number; platform: string; arch: string; packageType: string; fileName: string;
  fileSize?: number; sha256?: string; signatureValue?: string; signingKeyId?: string; status: string;
}
export interface ClientUpdatePolicy {
  id?: number; bizId: number; channel: string; platform: string; arch: string; updateEnabled: number;
  minimumSupportedVersion?: string; mandatoryReleaseId?: number; serverEnforcementEnabled: number;
  offlineGraceHours: number; checkIntervalSeconds: number; policyRevision?: number;
}
