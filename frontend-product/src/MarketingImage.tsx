import { marketingImages, type MarketingImageId } from "./marketingImages";

type MarketingImageProps = {
  image: MarketingImageId;
  alt: string;
  sizes: string;
  className?: string;
  loading?: "eager" | "lazy";
  fetchPriority?: "high" | "low" | "auto";
  "aria-hidden"?: boolean;
};

export function MarketingImage({ image, alt, sizes, className, loading = "lazy", fetchPriority = "auto", "aria-hidden": ariaHidden }: MarketingImageProps) {
  const asset = marketingImages[image];
  return <picture className="mk-responsive-picture">
    <source type="image/avif" srcSet={asset.avifSrcSet} sizes={sizes} />
    <source type="image/webp" srcSet={asset.webpSrcSet} sizes={sizes} />
    <img className={className} src={asset.src} srcSet={asset.fallbackSrcSet} sizes={sizes}
      alt={alt} aria-hidden={ariaHidden} width={asset.width} height={asset.height}
      loading={loading} fetchPriority={fetchPriority} decoding="async" />
  </picture>;
}
