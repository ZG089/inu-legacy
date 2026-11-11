/// a section name and the symbols in it: name, value, whether it is a function
type SyntheticTable = (&'static str, Vec<(&'static str, u64, bool)>);

use super::*;

const ELF_MAGIC: [u8; 4] = [0x7f, b'E', b'L', b'F'];
const ELFCLASS32: u8 = 1;
const ELFCLASS64: u8 = 2;
const ELFDATA2LSB: u8 = 1;
const ELFDATA2MSB: u8 = 2;
const EV_CURRENT: u8 = 1;
const EHDR32_SIZE: usize = 52;
const EHDR64_SIZE: usize = 64;
const SHDR32_SIZE: usize = 40;
const SHDR64_SIZE: usize = 64;
const SYM32_SIZE: usize = 16;
const SYM64_SIZE: usize = 24;
const SHN_UNDEF: u16 = 0;
const SHT_SYMTAB: u32 = 2;
const SHT_STRTAB: u32 = 3;
const SHT_DYNSYM: u32 = 11;

#[test]
fn lsposed_is_identified_by_soname_or_module_path() {
  let strings = b"\0other.so\0liblspd.so\0";
  let dynamic = [
    Dynamic {
      tag: DT_STRTAB,
      value: strings.as_ptr() as usize,
    },
    Dynamic { tag: DT_SONAME, value: 10 },
    Dynamic { tag: DT_NULL, value: 0 },
  ];
  assert_eq!(unsafe { read_soname(0, &dynamic) }, Some(c"liblspd.so"));
  assert!(is_lsposed_name(c"/data/adb/modules/zygisk_lsposed/zygisk/armeabi-v7a.so"));
  assert!(!is_lsposed_name(c"/data/app/desu.inugram/lib/armeabi/liblsplant.so"));
}

/// Builds an ELF image with the symbol tables described, plus an optional `.gnu_debugdata` section.
struct Builder {
  class: u8,
  tables: Vec<SyntheticTable>,
  debugdata: Option<Vec<u8>>,
}

impl Builder {
  fn new() -> Builder {
    Builder {
      class: ELFCLASS64,
      tables: Vec::new(),
      debugdata: None,
    }
  }

  fn elf32(mut self) -> Builder {
    self.class = ELFCLASS32;
    self
  }

  fn table(mut self, name: &'static str, symbols: &[(&'static str, u64, bool)]) -> Builder {
    self.tables.push((name, symbols.to_vec()));
    self
  }

  fn debugdata(mut self, inner: Vec<u8>) -> Builder {
    let mut compressed = Vec::new();
    lzma_rs::xz_compress(&mut std::io::Cursor::new(&inner), &mut compressed).unwrap();
    self.debugdata = Some(compressed);
    self
  }

  fn build(self) -> Vec<u8> {
    let is_32 = self.class == ELFCLASS32;
    let header_size = if is_32 { EHDR32_SIZE } else { EHDR64_SIZE };
    let section_size = if is_32 { SHDR32_SIZE } else { SHDR64_SIZE };
    let symbol_size = if is_32 { SYM32_SIZE } else { SYM64_SIZE };

    let mut shstrtab = vec![0u8];
    let append_symbol_name = |strings: &mut Vec<u8>, text: &str| {
      let at = strings.len() as u32;
      strings.extend_from_slice(text.as_bytes());
      strings.push(0);
      at
    };

    // section 0 is the mandatory null entry
    let mut sections: Vec<Vec<u8>> = vec![vec![0u8; section_size]];
    let mut blobs: Vec<Vec<u8>> = vec![Vec::new()];

    for (name, symbols) in &self.tables {
      let mut strtab = vec![0u8];
      let mut entries = Vec::new();
      for (symbol, value, defined) in symbols {
        let at = append_symbol_name(&mut strtab, symbol);
        let mut entry = vec![0u8; symbol_size];
        entry[0..4].copy_from_slice(&at.to_le_bytes());
        if is_32 {
          entry[4..8].copy_from_slice(&(*value as u32).to_le_bytes());
          entry[12] = 1;
          entry[14..16].copy_from_slice(&if *defined { 1u16 } else { SHN_UNDEF }.to_le_bytes());
        } else {
          entry[4] = 1;
          entry[6..8].copy_from_slice(&if *defined { 1u16 } else { SHN_UNDEF }.to_le_bytes());
          entry[8..16].copy_from_slice(&value.to_le_bytes());
        }
        entries.extend_from_slice(&entry);
      }

      let strtab_index = sections.len() + 1;
      sections.push(section_header(
        self.class,
        append_symbol_name(&mut shstrtab, name),
        if *name == ".dynsym" { SHT_DYNSYM } else { SHT_SYMTAB },
        strtab_index as u32,
        symbol_size as u64,
      ));
      blobs.push(entries);
      sections.push(section_header(self.class, append_symbol_name(&mut shstrtab, ".strtab"), SHT_STRTAB, 0, 0));
      blobs.push(strtab);
    }

    if let Some(compressed) = &self.debugdata {
      sections.push(section_header(self.class, append_symbol_name(&mut shstrtab, ".gnu_debugdata"), 1, 0, 0));
      blobs.push(compressed.clone());
    }

    sections.push(section_header(self.class, append_symbol_name(&mut shstrtab, ".shstrtab"), SHT_STRTAB, 0, 0));
    let shstrndx = sections.len() - 1;
    blobs.push(shstrtab);

    let mut image = vec![0u8; header_size];
    image[0..4].copy_from_slice(&ELF_MAGIC);
    image[4] = self.class;
    image[5] = ELFDATA2LSB;
    image[6] = EV_CURRENT;

    for (index, blob) in blobs.iter().enumerate() {
      let offset = image.len() as u64;
      set_section_data(&mut sections[index], self.class, offset, blob.len() as u64);
      image.extend_from_slice(blob);
    }
    // section 0 stays all-zero whatever offset the loop above wrote into it
    sections[0] = vec![0u8; section_size];

    let shoff = image.len() as u64;
    for section in &sections {
      image.extend_from_slice(section);
    }

    if is_32 {
      image[0x20..0x24].copy_from_slice(&(shoff as u32).to_le_bytes());
      image[0x2e..0x30].copy_from_slice(&(section_size as u16).to_le_bytes());
      image[0x30..0x32].copy_from_slice(&(sections.len() as u16).to_le_bytes());
      image[0x32..0x34].copy_from_slice(&(shstrndx as u16).to_le_bytes());
    } else {
      image[0x28..0x30].copy_from_slice(&shoff.to_le_bytes());
      image[0x3a..0x3c].copy_from_slice(&(section_size as u16).to_le_bytes());
      image[0x3c..0x3e].copy_from_slice(&(sections.len() as u16).to_le_bytes());
      image[0x3e..0x40].copy_from_slice(&(shstrndx as u16).to_le_bytes());
    }
    image
  }
}

fn section_header(class: u8, name: u32, kind: u32, link: u32, entsize: u64) -> Vec<u8> {
  let is_32 = class == ELFCLASS32;
  let mut section = vec![0u8; if is_32 { SHDR32_SIZE } else { SHDR64_SIZE }];
  section[0..4].copy_from_slice(&name.to_le_bytes());
  section[4..8].copy_from_slice(&kind.to_le_bytes());
  if is_32 {
    section[0x18..0x1c].copy_from_slice(&link.to_le_bytes());
    section[0x24..0x28].copy_from_slice(&(entsize as u32).to_le_bytes());
  } else {
    section[0x28..0x2c].copy_from_slice(&link.to_le_bytes());
    section[0x38..0x40].copy_from_slice(&entsize.to_le_bytes());
  }
  section
}

fn set_section_data(section: &mut [u8], class: u8, offset: u64, size: u64) {
  if class == ELFCLASS32 {
    section[0x10..0x14].copy_from_slice(&(offset as u32).to_le_bytes());
    section[0x14..0x18].copy_from_slice(&(size as u32).to_le_bytes());
  } else {
    section[0x18..0x20].copy_from_slice(&offset.to_le_bytes());
    section[0x20..0x28].copy_from_slice(&size.to_le_bytes());
  }
}

#[test]
fn a_symbol_is_found_in_dynsym() {
  let image = Builder::new().table(".dynsym", &[("_ZN3art9ArtMethod6InvokeEv", 0x1234, true)]).build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("_ZN3art9ArtMethod6InvokeEv"), Some(0x1234));
  assert_eq!(symbols.exact("_ZN3art9ArtMethod6InvokeE"), None);
}

#[test]
fn an_elf32_symbol_is_found_in_dynsym() {
  let image = Builder::new().elf32().table(".dynsym", &[("_ZN3art9ArtMethod6InvokeEv", 0x1234, true)]).build();
  let symbols = Symbols::parse(image).expect("parses ELF32");
  assert_eq!(symbols.exact("_ZN3art9ArtMethod6InvokeEv"), Some(0x1234));
  assert_eq!(symbols.prefix("_ZN3art9ArtMethod"), Some(0x1234));
}

#[test]
fn an_elf32_symtab_is_searched_when_dynsym_has_no_match() {
  let image = Builder::new()
    .elf32()
    .table(".dynsym", &[("exported", 0x10, true)])
    .table(".symtab", &[("_ZN3art3Foo3BarEv", 0x40, true)])
    .build();
  let symbols = Symbols::parse(image).expect("parses ELF32");
  assert_eq!(symbols.exact("_ZN3art3Foo3BarEv"), Some(0x40));
}

#[test]
fn a_prefix_matches_the_first_defined_symbol_that_starts_with_it() {
  let image = Builder::new()
    .table(
      ".dynsym",
      &[("_ZN3art11ClassLinker5OtherEv", 0x10, true), ("_ZN3art11ClassLinker11SetEntryPointsEv", 0x20, true)],
    )
    .build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.prefix("_ZN3art11ClassLinker11Set"), Some(0x20));
  assert_eq!(symbols.prefix("_ZN3art11ClassLinker"), Some(0x10));
  assert_eq!(symbols.prefix("_ZN3art9Something"), None);
}

/// libart's .dynsym carries plenty of undefined symbols, and they name no address
#[test]
fn an_undefined_or_zero_valued_symbol_is_not_an_answer_in_either_table() {
  let image = Builder::new()
    .table(".dynsym", &[("memcpy", 0x99, false), ("memcpy", 0x77, true)])
    .table(".symtab", &[("_ZN3art3Foo3BarEv", 0, true), ("_ZN3art3Foo3BarEv", 0x40, true)])
    .build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("memcpy"), Some(0x77));
  assert_eq!(symbols.exact("_ZN3art3Foo3BarEv"), Some(0x40));
}

#[test]
fn the_symtab_inside_gnu_debugdata_is_searched() {
  // the platform's own libart.so keeps its real symtab here, xz-compressed
  let inner = Builder::new().table(".symtab", &[("_ZN3art6Hidden4ThingEv", 0x555, true)]).build();
  let image = Builder::new().table(".dynsym", &[("exported", 0x10, true)]).debugdata(inner).build();
  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("_ZN3art6Hidden4ThingEv"), Some(0x555));
  assert_eq!(symbols.prefix("_ZN3art6Hidden"), Some(0x555));
  assert_eq!(symbols.exact("exported"), Some(0x10));
}

#[test]
fn the_symtab_inside_elf32_gnu_debugdata_is_searched() {
  let inner = Builder::new().elf32().table(".symtab", &[("_ZN3art6Hidden4ThingEv", 0x555, true)]).build();
  let image = Builder::new().elf32().table(".dynsym", &[("exported", 0x10, true)]).debugdata(inner).build();
  let symbols = Symbols::parse(image).expect("parses ELF32");
  assert_eq!(symbols.exact("_ZN3art6Hidden4ThingEv"), Some(0x555));
  assert_eq!(symbols.exact("exported"), Some(0x10));
}

#[test]
fn a_corrupt_gnu_debugdata_leaves_the_outer_tables_usable() {
  let mut image = Builder::new()
    .table(".dynsym", &[("exported", 0x10, true)])
    .debugdata(Builder::new().table(".symtab", &[("inner", 0x20, true)]).build())
    .build();
  // the xz stream starts with a 6-byte magic; break it without moving anything
  let at = image.windows(6).position(|w| w == [0xfd, b'7', b'z', b'X', b'Z', 0x00]).expect("xz magic");
  image[at + 1] = b'8';

  let symbols = Symbols::parse(image).expect("parses");
  assert_eq!(symbols.exact("exported"), Some(0x10));
  assert_eq!(symbols.exact("inner"), None);
}

#[test]
fn a_truncated_image_is_refused_rather_than_read_past() {
  let full = Builder::new().table(".dynsym", &[("exported", 0x10, true)]).build();
  for length in [0, 4, 16, EHDR64_SIZE, full.len() - 1] {
    assert!(Symbols::parse(full[..length].to_vec()).is_none(), "accepted {length} bytes");
  }
}

#[test]
fn a_big_endian_image_is_refused() {
  let mut image = Builder::new().build();
  image[5] = ELFDATA2MSB;
  assert!(Symbols::parse(image).is_none());
}

#[test]
fn an_unknown_elf_class_is_refused() {
  let mut image = Builder::new().build();
  image[4] = 3;
  assert!(Symbols::parse(image).is_none());
}

#[test]
fn an_image_with_no_symbol_table_at_all_is_not_a_source() {
  let image = Builder::new().build();
  assert!(Symbols::parse(image).is_none());
}

#[test]
fn the_dynamic_entry_layout_matches_the_process_word_size() {
  assert_eq!(std::mem::size_of::<Dynamic>(), 2 * std::mem::size_of::<usize>());
}

#[test]
fn the_bias_is_added_to_every_answer() {
  let image = Builder::new().table(".dynsym", &[("exported", 0x10, true)]).build();
  let loaded = LoadedImage {
    bias: 0x7000,
    symbols: Symbols::parse(image).expect("parses"),
  };
  assert_eq!(loaded.exact("exported"), 0x7010 as *mut c_void);
  assert_eq!(loaded.prefix("expo"), 0x7010 as *mut c_void);
  assert_eq!(loaded.exact("missing"), std::ptr::null_mut());
}

#[test]
fn a_resolver_over_no_image_answers_null_rather_than_failing() {
  let mut resolver = Resolver { image: None, seen: HashMap::new() };
  assert_eq!(resolver.exact("anything"), std::ptr::null_mut());
  assert_eq!(resolver.prefix("any"), std::ptr::null_mut());
}

#[test]
fn exact_and_prefix_answers_do_not_share_a_cache_entry() {
  let image = Builder::new().table(".dynsym", &[("prefixed_and_more", 0x10, true)]).build();
  let loaded = LoadedImage {
    bias: 0,
    symbols: Symbols::parse(image).expect("parses"),
  };
  let mut resolver = Resolver {
    image: Some(loaded),
    seen: HashMap::new(),
  };

  assert_eq!(resolver.exact("prefixed"), std::ptr::null_mut());
  assert_eq!(resolver.prefix("prefixed"), 0x10 as *mut c_void);
  assert_eq!(resolver.exact("prefixed"), std::ptr::null_mut());
}
