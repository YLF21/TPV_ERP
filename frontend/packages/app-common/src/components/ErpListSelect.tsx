import { ErpMultiSelect, type ErpMultiSelectProps } from "./ErpMultiSelect";
import { ErpSelect } from "./ErpSelect";

type Props = Omit<ErpMultiSelectProps, "placeholder"> & {
  placeholder?: string;
  single?: boolean;
  onCommit?: () => void;
  onNavigatePrevious?: () => void;
};

/** Option filters share one desktop menu. The single mode preserves PDA controls. */
export function ErpListSelect({
  values, options, onChange, placeholder = options.find(option => !option.value)?.label ?? "—",
  single = false, onCommit, onNavigatePrevious, maxPopoverWidth,
  emptyLabel, searchPlaceholder, customValueLabel, ...control
}: Props) {
  if (single) {
    return <ErpSelect {...control} value={values[0] ?? ""} options={options}
      onChange={value => onChange(value ? [value] : [])} onCommit={onCommit}
      onNavigatePrevious={onNavigatePrevious} maxPopoverWidth={maxPopoverWidth} />;
  }

  return <ErpMultiSelect {...control} values={values} options={options} onChange={onChange}
    placeholder={placeholder} maxPopoverWidth={maxPopoverWidth ?? "trigger"} emptyLabel={emptyLabel}
    searchPlaceholder={searchPlaceholder} customValueLabel={customValueLabel} />;
}
