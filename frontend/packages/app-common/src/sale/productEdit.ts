import { apiRequest } from "../api/client";
import type {
  DiscountTypeCode,
  PriceUseModeCode,
  ProductCreateEditProduct,
  ProductTypeCode,
} from "../components/ProductCreateDialog";

type ProductManagementView = {
  id: string;
  familyId: string;
  subfamilyId?: string | null;
  taxId: string;
  productType: ProductTypeCode;
  discountType: DiscountTypeCode;
  priceUseMode: PriceUseModeCode;
  name: string;
  description?: string | null;
  comments?: string | null;
  purchasePrice: number | string;
  purchaseDiscountPercent?: number | string | null;
  stockMin?: number | string | null;
  stockMax?: number | string | null;
  packageQuantity?: number | string | null;
  active: boolean;
  taxesIncluded: boolean;
  offerActive: boolean;
  offerFrom?: string | null;
  offerUntil?: string | null;
  offerDiscountPercent?: number | string | null;
  imageId?: string | null;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  salePrice: number | string;
  memberPrice?: number | string | null;
  wholesalePrice?: number | string | null;
  offerPrice?: number | string | null;
};

export type ProductEditAuthorization = {
  operationId: string;
  authorizedBy: string;
  delegated: boolean;
  expiresAt: string;
  product: ProductManagementView;
};

export async function authorizeProductEdit(
  productId: string,
  token?: string,
  authorizerUsername?: string,
  authorizerPassword?: string,
) {
  return apiRequest<ProductEditAuthorization>("/pos/product-edit-authorizations", {
    token,
    body: {
      productId,
      ...(authorizerUsername ? { authorizerUsername } : {}),
      ...(authorizerPassword ? { authorizerPassword } : {}),
    }
  });
}

export async function revokeProductEditAuthorization(operationId: string, token?: string) {
  return apiRequest<void>(`/pos/product-edit-authorizations/${encodeURIComponent(operationId)}`, {
    method: "DELETE",
    token,
  });
}

export function productEditDialogValue(product: ProductManagementView): ProductCreateEditProduct {
  return {
    id: product.id,
    imageId: product.imageId ?? null,
    form: {
      active: product.active,
      familyId: product.familyId,
      subfamilyId: product.subfamilyId ?? "",
      taxId: product.taxId,
      productType: product.productType,
      priceUseMode: product.priceUseMode,
      discountType: product.discountType,
      name: product.name,
      description: product.description ?? "",
      comments: product.comments ?? "",
      purchasePrice: text(product.purchasePrice),
      taxesIncluded: product.taxesIncluded,
      code: product.code ?? "",
      barcode: product.barcode ?? "",
      barcode2: product.barcode2 ?? "",
      salePrice: text(product.salePrice),
      memberPrice: text(product.memberPrice),
      wholesalePrice: text(product.wholesalePrice),
      offerPrice: text(product.offerPrice),
      offerDiscountPercent: text(product.offerDiscountPercent),
      offerActive: product.offerActive,
      offerFrom: product.offerFrom ?? "",
      offerUntil: product.offerUntil ?? "",
    },
    initialData: {
      discountType: product.discountType,
      purchaseDiscountPercent: product.purchaseDiscountPercent ?? null,
      packageQuantity: product.packageQuantity ?? null,
      stockMin: product.stockMin ?? null,
      stockMax: product.stockMax ?? null,
    }
  };
}

function text(value: number | string | null | undefined) {
  return value == null ? "" : String(value);
}
