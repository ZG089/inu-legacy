use object::elf::{FileHeader32, FileHeader64, SHT_DYNSYM, SHT_SYMTAB};
use object::read::elf::{FileHeader, SectionHeader, Sym, SymbolTable};
use object::LittleEndian;
use std::collections::HashMap;
#[cfg(target_os = "android")]
use std::ffi::c_int;
use std::ffi::{c_char, c_void, CStr};
use std::fs;

type Endian = LittleEndian;

const ELFCLASS32: u8 = 1;
const ELFCLASS64: u8 = 2;

#[repr(C)]
struct Dynamic {
  tag: isize,
  value: usize,
}

const DT_NULL: isize = 0;
const DT_STRTAB: isize = 5;
const DT_SONAME: isize = 14;

unsafe fn read_soname(load_bias: usize, dynamic: &[Dynamic]) -> Option<&CStr> {
  let mut strings = None;
  let mut soname = None;
  for entry in dynamic {
    match entry.tag {
      DT_NULL => break,
      DT_STRTAB => strings = Some(entry.value),
      DT_SONAME => soname = Some(entry.value),
      _ => {}
    }
  }
  let address = load_bias.checked_add(strings?)?.checked_add(soname?)?;
  // SAFETY: the dynamic loader validated this string table and SONAME before exposing the image
  Some(unsafe { CStr::from_ptr(address as *const c_char) })
}

fn is_lsposed_name(name: &CStr) -> bool {
  let name = name.to_bytes();
  name.ends_with(b"/liblspd.so") || name.windows(b"/zygisk_lsposed/".len()).any(|part| part == b"/zygisk_lsposed/")
}

#[cfg(target_os = "android")]
unsafe extern "C" fn find_lsposed(info: *mut libc::dl_phdr_info, _size: usize, _data: *mut c_void) -> c_int {
  // SAFETY: bionic hands over an entry and its program headers for the duration of this call
  let info = unsafe { &*info };
  if !info.dlpi_name.is_null() && is_lsposed_name(unsafe { CStr::from_ptr(info.dlpi_name) }) {
    return 1;
  }
  if info.dlpi_phdr.is_null() {
    return 0;
  }
  let headers = unsafe { std::slice::from_raw_parts(info.dlpi_phdr, info.dlpi_phnum as usize) };
  let Some(dynamic) = headers.iter().find(|header| header.p_type == libc::PT_DYNAMIC) else {
    return 0;
  };
  let Some(address) = (info.dlpi_addr as usize).checked_add(dynamic.p_vaddr as usize) else {
    return 0;
  };
  let entries = unsafe {
    std::slice::from_raw_parts(address as *const Dynamic, dynamic.p_memsz as usize / std::mem::size_of::<Dynamic>())
  };
  unsafe { read_soname(info.dlpi_addr as usize, entries) }.is_some_and(|name| name.to_bytes() == b"liblspd.so") as c_int
}

#[cfg(target_os = "android")]
pub fn is_lsposed_loaded() -> bool {
  // SAFETY: `find_lsposed` does not retain bionic's callback arguments
  unsafe { libc::dl_iterate_phdr(Some(find_lsposed), std::ptr::null_mut()) != 0 }
}

#[cfg(not(target_os = "android"))]
pub fn is_lsposed_loaded() -> bool {
  false
}

fn has_symbol_table_in<Elf>(image: &[u8]) -> bool
where
  Elf: FileHeader<Endian = Endian>,
{
  let Ok(header) = Elf::parse(image) else {
    return false;
  };
  let Ok(endian) = header.endian() else {
    return false;
  };
  let Ok(sections) = header.sections(endian, image) else {
    return false;
  };
  sections.enumerate().any(|(_, section)| matches!(section.sh_type(endian), SHT_DYNSYM | SHT_SYMTAB))
}

fn has_symbol_table(image: &[u8]) -> bool {
  match image.get(4) {
    Some(&ELFCLASS32) => has_symbol_table_in::<FileHeader32<Endian>>(image),
    Some(&ELFCLASS64) => has_symbol_table_in::<FileHeader64<Endian>>(image),
    _ => false,
  }
}

fn read_debugdata_in<Elf>(image: &[u8]) -> Option<Vec<u8>>
where
  Elf: FileHeader<Endian = Endian>,
{
  let header = Elf::parse(image).ok()?;
  let endian = header.endian().ok()?;
  let sections = header.sections(endian, image).ok()?;
  let (_, section) = sections.section_by_name(endian, b".gnu_debugdata")?;
  decompress_xz(section.data(endian, image).ok()?)
}

fn read_debugdata(image: &[u8]) -> Option<Vec<u8>> {
  match image.get(4) {
    Some(&ELFCLASS32) => read_debugdata_in::<FileHeader32<Endian>>(image),
    Some(&ELFCLASS64) => read_debugdata_in::<FileHeader64<Endian>>(image),
    _ => None,
  }
}

fn find_symbols_in<Elf>(image: &[u8], matches: &impl Fn(&str) -> bool) -> Option<u64>
where
  Elf: FileHeader<Endian = Endian>,
{
  let header = Elf::parse(image).ok()?;
  let endian = header.endian().ok()?;
  let sections = header.sections(endian, image).ok()?;
  for (index, section) in sections.enumerate() {
    if !matches!(section.sh_type(endian), SHT_DYNSYM | SHT_SYMTAB) {
      continue;
    }
    let Ok(table) = SymbolTable::parse(endian, image, &sections, index, section) else {
      continue;
    };
    for symbol in table.symbols() {
      let value: u64 = symbol.st_value(endian).into();
      if value == 0 || symbol.is_undefined(endian) {
        continue;
      }
      let Some(name) = symbol.name(endian, table.strings()).ok().and_then(|name| std::str::from_utf8(name).ok()) else {
        continue;
      };
      if matches(name) {
        return Some(value);
      }
    }
  }
  None
}

fn find_symbols(image: &[u8], matches: &impl Fn(&str) -> bool) -> Option<u64> {
  match image.get(4) {
    Some(&ELFCLASS32) => find_symbols_in::<FileHeader32<Endian>>(image, matches),
    Some(&ELFCLASS64) => find_symbols_in::<FileHeader64<Endian>>(image, matches),
    _ => None,
  }
}

pub struct Symbols {
  image: Vec<u8>,
  debug: Vec<u8>,
}

impl Symbols {
  pub fn parse(image: Vec<u8>) -> Option<Symbols> {
    let debug = read_debugdata(&image).unwrap_or_default();
    let symbols = Symbols { image, debug };
    (has_symbol_table(&symbols.image) || has_symbol_table(&symbols.debug)).then_some(symbols)
  }

  fn find(&self, matches: impl Fn(&str) -> bool) -> Option<u64> {
    find_symbols(&self.image, &matches).or_else(|| find_symbols(&self.debug, &matches))
  }

  pub fn exact(&self, name: &str) -> Option<u64> {
    self.find(|candidate| candidate == name)
  }

  pub fn prefix(&self, prefix: &str) -> Option<u64> {
    self.find(|candidate| candidate.starts_with(prefix))
  }
}

fn decompress_xz(compressed: &[u8]) -> Option<Vec<u8>> {
  let mut out = Vec::new();
  lzma_rs::xz_decompress(&mut std::io::Cursor::new(compressed), &mut out).ok()?;
  Some(out)
}

#[cfg(target_os = "android")]
struct Search {
  wanted: &'static str,
  found: Option<(usize, String)>,
}

#[cfg(target_os = "android")]
unsafe extern "C" fn visit(info: *mut libc::dl_phdr_info, _size: usize, data: *mut c_void) -> c_int {
  // SAFETY: `find_loaded` passes its `Search` as `data`, and bionic hands over an entry valid for
  // this call whose name, when set, is a C string
  let (search, info) = unsafe { (&mut *(data as *mut Search), &*info) };
  if info.dlpi_name.is_null() {
    return 0;
  }
  let Ok(name) = unsafe { CStr::from_ptr(info.dlpi_name) }.to_str() else {
    return 0;
  };
  if !name.ends_with(search.wanted) {
    return 0;
  }
  search.found = Some((info.dlpi_addr as usize, name.to_string()));
  1
}

#[cfg(target_os = "android")]
fn find_loaded(name: &'static str) -> Option<(usize, String)> {
  let mut search = Search { wanted: name, found: None };
  // SAFETY: `visit` reads `data` as the `Search` it is given here, which outlives the call
  unsafe { libc::dl_iterate_phdr(Some(visit), &mut search as *mut Search as *mut c_void) };
  search.found
}

#[cfg(not(target_os = "android"))]
fn find_loaded(_name: &'static str) -> Option<(usize, String)> {
  None
}

pub struct LoadedImage {
  bias: usize,
  symbols: Symbols,
}

impl LoadedImage {
  pub fn open(name: &'static str) -> Option<LoadedImage> {
    let (bias, path) = find_loaded(name)?;
    let image = fs::read(&path).ok()?;
    Some(LoadedImage { bias, symbols: Symbols::parse(image)? })
  }

  fn address(&self, value: Option<u64>) -> *mut c_void {
    match value.and_then(|value| usize::try_from(value).ok()) {
      Some(value) => self.bias.wrapping_add(value) as *mut c_void,
      None => std::ptr::null_mut(),
    }
  }

  pub fn exact(&self, name: &str) -> *mut c_void {
    self.address(self.symbols.exact(name))
  }

  pub fn prefix(&self, prefix: &str) -> *mut c_void {
    self.address(self.symbols.prefix(prefix))
  }
}

pub struct Resolver {
  image: Option<LoadedImage>,
  seen: HashMap<String, usize>,
}

impl Resolver {
  pub fn open(name: &'static str) -> Resolver {
    Resolver {
      image: LoadedImage::open(name),
      seen: HashMap::new(),
    }
  }

  fn cached(&mut self, key: String, lookup: impl FnOnce(&LoadedImage) -> *mut c_void) -> *mut c_void {
    if let Some(address) = self.seen.get(&key) {
      return *address as *mut c_void;
    }
    let address = self.image.as_ref().map(lookup).unwrap_or(std::ptr::null_mut());
    self.seen.insert(key, address as usize);
    address
  }

  pub fn exact(&mut self, name: &str) -> *mut c_void {
    self.cached(name.to_string(), |image| image.exact(name))
  }

  pub fn prefix(&mut self, prefix: &str) -> *mut c_void {
    self.cached(format!("{prefix} "), |image| image.prefix(prefix))
  }
}

#[cfg(test)]
#[path = "elf_tests.rs"]
mod tests;
