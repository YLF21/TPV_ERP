import { useEffect, useState } from "react";
import { apiBaseUrl } from "../api/runtime";

export function ProductThumbnail({
  productId,
  imageId,
  name = "",
  token,
  className = "bulk-image",
  alt = "",
}: {
  productId: string;
  imageId?: string | null;
  name?: string | null;
  token: string;
  className?: string;
  alt?: string;
}) {
  const [source, setSource] = useState("");

  useEffect(() => {
    if (!imageId || !token) {
      setSource("");
      return;
    }
    let active = true;
    let objectUrl = "";
    void fetch(
      `${apiBaseUrl}/products/${encodeURIComponent(productId)}/image?thumbnail=true`,
      { headers: { Authorization: `Bearer ${token}` } },
    )
      .then((response) =>
        response.ok
          ? response.blob()
          : Promise.reject(new Error("product_image_unavailable")),
      )
      .then((blob) => {
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setSource(objectUrl);
      })
      .catch(() => {
        if (active) setSource("");
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [imageId, productId, token]);

  return (
    <div className={className}>
      {source ? (
        <img src={source} alt={alt} />
      ) : (
        <span>{name?.slice(0, 1).toLocaleUpperCase() || "-"}</span>
      )}
    </div>
  );
}
